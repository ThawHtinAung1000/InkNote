package wlv.ac.uk.inknote.ui.editor;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

/** Exposes selection changes without polling or adding another text watcher. */
public final class SelectionEditText extends AppCompatEditText {
    private Runnable selectionChangedListener;

    public SelectionEditText(@NonNull Context context) {
        super(context);
    }

    public SelectionEditText(@NonNull Context context, @Nullable AttributeSet attributes) {
        super(context, attributes);
    }

    public SelectionEditText(@NonNull Context context, @Nullable AttributeSet attributes, int style) {
        super(context, attributes, style);
    }

    public void setOnSelectionChangedListener(@Nullable Runnable listener) {
        selectionChangedListener = listener;
    }

    @Override
    protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        if (selectionChangedListener != null) selectionChangedListener.run();
    }
}
