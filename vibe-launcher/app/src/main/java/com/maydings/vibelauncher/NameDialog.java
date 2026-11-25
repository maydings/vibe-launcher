package com.maydings.vibelauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * The TV's keyboard on a name, in the launcher's own panel: what it's called now, to change, then OK (or the
 * keyboard's Done) with what was typed, or Cancel. For a tile, a folder and a Library item.
 */
final class NameDialog {

    private NameDialog() {
    }

    /** max: the longest name it takes (0: any). highlight: the focus colour of the screen it's over. */
    static void ask(Activity activity, int title, String current, int max, int highlight, Consumer<String> done) {
        ask(activity, title, current, max, highlight, false, false, done);
    }

    /** A password/passphrase prompt (hidden characters) in the same panel. */
    static void askSecret(Activity activity, int title, int max, int highlight, Consumer<String> done) {
        ask(activity, title, "", max, highlight, true, false, done);
    }

    /**
     * A new passphrase, typed twice in the one panel (hidden characters, so a slip in one would go unseen):
     * taken only when both are the same.
     */
    static void askNewSecret(Activity activity, int title, int max, int highlight, Consumer<String> done) {
        ask(activity, title, "", max, highlight, true, true, done);
    }

    private static void ask(Activity activity, int title, String current, int max, int highlight, boolean secret,
                            boolean twice, Consumer<String> done) {
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_update,
                activity.findViewById(android.R.id.content), false);
        ((TextView) panel.findViewById(R.id.updateTitle)).setText(title);
        panel.findViewById(R.id.notesScroll).setVisibility(View.GONE);
        EditText name = panel.findViewById(R.id.field);
        name.setVisibility(View.VISIBLE);
        if (secret) {
            name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        name.setText(current);
        if (max > 0) name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        name.setSelection(name.length());   // to add to it, or take from its end
        name.setBackgroundTintList(ColorStateList.valueOf(highlight));
        EditText again = panel.findViewById(R.id.fieldAgain);
        if (twice) {
            again.setVisibility(View.VISIBLE);
            if (max > 0) again.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
            again.setBackgroundTintList(ColorStateList.valueOf(highlight));
            name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                    | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        }
        TextView ok = panel.findViewById(R.id.install), cancel = panel.findViewById(R.id.cancelUpdate);
        ok.setText(android.R.string.ok);
        Highlight.pill(ok, highlight);
        Highlight.pill(cancel, highlight);
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Math.round(480 * activity.getResources().getDisplayMetrics().density),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            // Near the top, clear of the keyboard (which takes the lower half), and the keyboard at once.
            window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams at = window.getAttributes();
            at.y = Math.round(40 * activity.getResources().getDisplayMetrics().density);
            window.setAttributes(at);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        Runnable save = () -> {
            if (twice && !name.getText().toString().equals(again.getText().toString())) {
                // Not the same: the panel stays, the second cleared to be typed again.
                MainActivity.showError(activity, R.string.backup_passphrase_mismatch);
                again.setText("");
                again.requestFocus();
                return;
            }
            dialog.dismiss();
            done.accept(name.getText().toString());
        };
        ok.setOnClickListener(v -> save.run());
        cancel.setOnClickListener(v -> dialog.dismiss());
        name.setOnEditorActionListener((v, action, event) -> {
            if (twice) again.requestFocus();   // on to the second
            else save.run();
            return true;
        });
        again.setOnEditorActionListener((v, action, event) -> {
            save.run();
            return true;
        });
        dialog.show();
        name.requestFocus();
    }
}
