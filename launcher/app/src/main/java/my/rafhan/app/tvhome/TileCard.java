package my.rafhan.app.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A home card with a heading and up to three tiles (picture, name, small note). Used for
 * Continue Watching and Recommended. D-pad focus is driven by MainActivity; this class only
 * builds the views and hands out the tiles.
 */
final class TileCard {
    static final class Item {
        String key, label, note, detail;
        Bitmap bitmap;
        Intent intent;
        boolean icon; // an app tile (landscape), not a poster
    }

    interface Listener {
        void onVisibilityChanged();
    }

    static final int MAX = 4;
    private static final int TILE_W = 80, TILE_H = 120, GAP = 8; // dp, a 2:3 poster

    private final Activity activity;
    private final LinearLayout card;
    private final Listener listener;
    private final float dp;
    private final List<Item> items = new ArrayList<>();
    private final List<ImageView> tiles = new ArrayList<>();
    private String heading = "";
    private TextView headingView;
    private int focus = -1;

    TileCard(Activity activity, LinearLayout card, Listener listener) {
        this.activity = activity;
        this.card = card;
        this.listener = listener;
        this.dp = activity.getResources().getDisplayMetrics().density;
        card.setClipChildren(false);
        card.setClipToPadding(false);
    }

    boolean hasItems() {
        return !items.isEmpty();
    }

    int count() {
        return items.size();
    }

    Item item(int i) {
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    View tile(int i) {
        return i >= 0 && i < tiles.size() ? tiles.get(i) : null;
    }

    void setItems(String heading, List<Item> list) {
        boolean was = hasItems();
        this.heading = heading;
        items.clear();
        items.addAll(list.size() > MAX ? list.subList(0, MAX) : list);
        render();
        if (was != hasItems()) listener.onVisibilityChanged();
    }

    /** A picture arrived after the card was built. */
    void setBitmap(String key, Bitmap b) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (key.equals(it.key)) {
                it.bitmap = b;
                tiles.get(i).setImageBitmap(b);
            }
        }
    }

    /** Brightens the heading while one of this card's posters has focus (-1: none). */
    void setFocus(int idx) {
        focus = idx;
        updateHeading();
    }

    private void updateHeading() {
        if (headingView == null) return;
        headingView.setText(heading);
        headingView.setTextColor(item(focus) != null ? 0xFFFFFFFF : 0xCCFFFFFF);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void render() {
        card.removeAllViews();
        tiles.clear();
        headingView = null;
        if (items.isEmpty()) return;

        TextView h = new TextView(activity);
        h.setTextSize(13);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.END);
        h.setMaxWidth(px(MAX * TILE_W + (MAX - 1) * GAP));
        h.setShadowLayer(6, 0, 1, 0xCC000000);
        h.setTypeface(Fonts.outfit(activity, 500));
        card.addView(h);
        headingView = h;
        updateHeading();

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = px(4);
        card.addView(row, rlp);

        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            ImageView tile = new ImageView(activity);
            if (it.icon) {
                // App tile (landscape) in a poster-shaped glass slot.
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0x33FFFFFF);
                bg.setCornerRadius(px(11));
                tile.setBackground(bg);
                tile.setScaleType(ImageView.ScaleType.FIT_CENTER);
                tile.setPadding(px(4), px(4), px(4), px(4));
            } else {
                tile.setBackgroundResource(R.drawable.tile_bg);
                tile.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
            tile.setClipToOutline(true);
            if (it.bitmap != null) tile.setImageBitmap(it.bitmap);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(TILE_W), px(TILE_H));
            if (i > 0) tlp.setMarginStart(px(GAP));
            row.addView(tile, tlp);
            tiles.add(tile);
        }
    }
}
