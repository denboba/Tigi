package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.List;

/**
 * Tigi: the red heart is the "like". Only the plain heart counts, not sequences that start with it
 * (heart on fire, mending heart).
 */
public class TigiHeart {

    public static final String HEART = "❤";
    private static final String HEART_VS16 = "❤️";

    public static boolean isHeart(String emoticon) {
        return HEART.equals(emoticon) || HEART_VS16.equals(emoticon);
    }

    public static boolean isHeart(TLRPC.Reaction reaction) {
        return reaction instanceof TLRPC.TL_reactionEmoji && isHeart(((TLRPC.TL_reactionEmoji) reaction).emoticon);
    }

    /** The heart as the server spells it in the available reactions, or null if it is not there. */
    public static String find(List<TLRPC.TL_availableReaction> reactions) {
        if (reactions == null) {
            return null;
        }
        for (int i = 0; i < reactions.size(); i++) {
            final TLRPC.TL_availableReaction reaction = reactions.get(i);
            if (reaction != null && !reaction.inactive && isHeart(reaction.reaction)) {
                return reaction.reaction;
            }
        }
        return null;
    }
}
