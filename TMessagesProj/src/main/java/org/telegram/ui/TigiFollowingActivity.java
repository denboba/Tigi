package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/** Tigi: "Following" on the own profile, the channels the user is subscribed to; a tap opens the channel's profile. */
public class TigiFollowingActivity extends BaseFragment {

    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        channels.addAll(TigiPosting.followedChannels(currentAccount));
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.TigiProfileFollowingCount));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return true;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                final UserCell cell = new UserCell(parent.getContext(), 8, 0, false);
                cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(cell);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                final TLRPC.Chat chat = channels.get(position);
                final String status = chat.participants_count > 0
                    ? LocaleController.formatPluralStringComma("Subscribers", chat.participants_count)
                    : LocaleController.getString(R.string.ChannelPublic);
                ((UserCell) holder.itemView).setData(chat, chat.title, status, 0, position != channels.size() - 1);
            }

            @Override
            public int getItemCount() {
                return channels.size();
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < channels.size()) {
                TigiNavigation.openProfile(this, -channels.get(position).id);
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        if (channels.isEmpty()) {
            final TextView empty = new TextView(context);
            empty.setText(LocaleController.getString(R.string.TigiFollowingEmpty));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
            root.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }
        fragmentView = root;
        return fragmentView;
    }
}
