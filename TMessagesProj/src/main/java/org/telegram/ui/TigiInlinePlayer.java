package org.telegram.ui;

import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;

/**
 * Tigi: the one video player of a feed (Home, channel feed, reels). Only one video plays at a time; starting another
 * stops the previous one and the player is released whenever nothing plays, so memory stays bounded.
 * Muted by default, the mute choice is remembered. Telegram's VideoPlayer streams from the cloud while downloading.
 */
public class TigiInlinePlayer {

    public interface Listener {
        /** The first frame is on screen (hide the thumbnail), or playback failed (show it again). */
        void onStateChanged(MessageObject message, boolean rendering, boolean failed);
    }

    private static final String KEY_MUTED = "tigi_feed_muted";

    private final int account;
    private final String muteKey;
    private VideoPlayer player;
    private TextureView textureView;
    private ViewGroup host;
    private MessageObject message;
    private Listener listener;
    private boolean muted;
    private boolean looping = true;
    private boolean fitInside;

    public TigiInlinePlayer(int account) {
        this(account, KEY_MUTED);
    }

    public TigiInlinePlayer(int account, String muteKey) {
        this.account = account;
        this.muteKey = muteKey;
        muted = prefs().getBoolean(muteKey, true);
    }

    private SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
        if (player != null) {
            player.setLooping(looping);
        }
    }

    /** true: letterbox wide videos instead of cropping them (full screen reels). */
    public void setFitInside(boolean fitInside) {
        this.fitInside = fitInside;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        prefs().edit().putBoolean(muteKey, muted).apply();
        if (player != null) {
            player.setMute(muted);
            // with sound the player takes audio focus: calls and other apps pause or duck it
            player.handleAudioFocus(!muted);
        }
    }

    public MessageObject getMessage() {
        return message;
    }

    public boolean isPlaying(MessageObject message) {
        return message != null && this.message == message && player != null;
    }

    public boolean isPaused() {
        return player != null && !player.getPlayWhenReady();
    }

    public VideoPlayer getPlayer() {
        return player;
    }

    /** Plays message inside host (a FrameLayout over the thumbnail), stopping whatever played before. */
    public boolean play(ViewGroup host, MessageObject message, Listener listener) {
        if (host == null || message == null || message.getDocument() == null) {
            return false;
        }
        if (this.message == message && this.host == host && player != null) {
            player.play();
            return true;
        }
        stop();
        final TLRPC.Document document = message.getDocument();
        final Uri uri = FileStreamLoadOperation.prepareUri(account, document, message);
        if (uri == null) {
            return false;
        }
        this.host = host;
        this.message = message;
        this.listener = listener;

        textureView = new TextureView(host.getContext());
        textureView.setAlpha(0f);
        textureView.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // above the thumbnail, below the play/mute controls the page adds after its image
        host.addView(textureView, 1, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, android.view.Gravity.CENTER));

        final VideoPlayer videoPlayer = new VideoPlayer();
        player = videoPlayer;
        videoPlayer.setTextureView(textureView);
        videoPlayer.setLooping(looping);
        videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
            }

            @Override
            public void onError(VideoPlayer p, Exception e) {
                FileLog.e(e);
                if (p == player) {
                    final MessageObject failed = TigiInlinePlayer.this.message;
                    final Listener l = TigiInlinePlayer.this.listener;
                    stop();
                    if (l != null) {
                        l.onStateChanged(failed, false, true);
                    }
                }
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                fitTexture(width, height, unappliedRotationDegrees);
            }

            @Override
            public void onRenderedFirstFrame() {
                if (videoPlayer == player && textureView != null) {
                    textureView.setAlpha(1f);
                    if (TigiInlinePlayer.this.listener != null) {
                        TigiInlinePlayer.this.listener.onStateChanged(TigiInlinePlayer.this.message, true, false);
                    }
                }
            }

            @Override
            public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }
        });
        videoPlayer.preparePlayer(uri, "other");
        videoPlayer.setMute(muted);
        videoPlayer.handleAudioFocus(!muted);
        try {
            // headphones unplugged: pause instead of switching to the loudspeaker
            if (videoPlayer.player != null) {
                videoPlayer.player.setHandleAudioBecomingNoisy(true);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        videoPlayer.play();
        return true;
    }

    /** Keeps the video's own aspect ratio inside the host (center crop like the thumbnail). */
    private void fitTexture(int width, int height, int rotation) {
        if (textureView == null || host == null || width <= 0 || height <= 0) {
            return;
        }
        if (rotation == 90 || rotation == 270) {
            final int t = width;
            width = height;
            height = t;
        }
        final float hostW = host.getWidth();
        final float hostH = host.getHeight();
        if (hostW <= 0 || hostH <= 0) {
            return;
        }
        final float scale = fitInside ? Math.min(hostW / width, hostH / height) : Math.max(hostW / width, hostH / height);
        final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        params.width = (int) Math.ceil(width * scale);
        params.height = (int) Math.ceil(height * scale);
        textureView.setLayoutParams(params);
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void resume() {
        if (player != null) {
            player.play();
        }
    }

    /** Stops and releases the player and removes its view. Safe to call any time. */
    public void stop() {
        final VideoPlayer old = player;
        player = null;
        if (old != null) {
            try {
                old.setDelegate(null);
                old.releasePlayer(true);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        if (textureView != null && textureView.getParent() instanceof ViewGroup) {
            ((ViewGroup) textureView.getParent()).removeView(textureView);
        }
        final MessageObject stopped = message;
        final Listener l = listener;
        textureView = null;
        host = null;
        message = null;
        listener = null;
        if (l != null && stopped != null) {
            l.onStateChanged(stopped, false, false);
        }
    }
}
