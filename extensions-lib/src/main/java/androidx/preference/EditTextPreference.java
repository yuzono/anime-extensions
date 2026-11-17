package androidx.preference;

import android.content.Context;
import android.widget.EditText;

public class EditTextPreference extends DialogPreference {

  public EditTextPreference(Context context) {
    throw new RuntimeException("Stub!");
  }

  public String getText() {
    throw new RuntimeException("Stub!");
  }

  public void setText(String text) {
    throw new RuntimeException("Stub!");
  }

  public void setOnBindEditTextListener(OnBindEditTextListener onBindEditTextListener) {
    throw new RuntimeException("Stub!");
  }

  OnBindEditTextListener getOnBindEditTextListener() {
    throw new RuntimeException("Stub!");
  }

  public interface OnBindEditTextListener {
    void onBindEditText(EditText editText);
  }
}
