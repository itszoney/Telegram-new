package org.thunderdog.challegram.widget.voip;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class CallSoundSliderView extends LinearLayout {
  public interface OnSliderValueChangeListener {
    void onValueChanged (int value);
  }

  /** Optional formatter – receives the raw SeekBar progress and returns the display string. */
  public interface ValueFormatter {
    String format (int progress);
  }

  private final TextView labelView;
  private final TextView valueView;
  private final SeekBar seekBar;
  private OnSliderValueChangeListener listener;
  private @Nullable ValueFormatter valueFormatter;
  private String labelText;

  public CallSoundSliderView (@NonNull Context context) {
    super(context);
    setOrientation(VERTICAL);

    labelView = new TextView(context);
    valueView = new TextView(context);
    seekBar = new SeekBar(context);

    LinearLayout headerRow = new LinearLayout(context);
    headerRow.setOrientation(HORIZONTAL);
    LayoutParams labelParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
    headerRow.addView(labelView, labelParams);
    headerRow.addView(valueView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

    addView(headerRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    addView(seekBar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged (SeekBar seekBar, int progress, boolean fromUser) {
        updateValueLabel(progress);
        if (fromUser && listener != null) {
          listener.onValueChanged(progress);
        }
      }

      @Override
      public void onStartTrackingTouch (SeekBar seekBar) { }

      @Override
      public void onStopTrackingTouch (SeekBar seekBar) { }
    });
  }

  public void setLabel (String label) {
    this.labelText = label;
    labelView.setText(label);
  }

  public void setRange (int min, int max) {
    seekBar.setMax(max - min);
  }

  public void setValue (int value, int min) {
    seekBar.setProgress(value - min);
    updateValueLabel(seekBar.getProgress());
  }

  public void setOnValueChangeListener (@Nullable OnSliderValueChangeListener listener) {
    this.listener = listener;
  }

  /**
   * Supply a custom formatter so the value label can show e.g. "200%" instead of "2".
   * If null, falls back to the raw integer progress value.
   */
  public void setValueFormatter (@Nullable ValueFormatter formatter) {
    this.valueFormatter = formatter;
    updateValueLabel(seekBar.getProgress());
  }

  private void updateValueLabel (int progress) {
    String text = valueFormatter != null ? valueFormatter.format(progress) : String.valueOf(progress);
    valueView.setText(text);
  }
}
