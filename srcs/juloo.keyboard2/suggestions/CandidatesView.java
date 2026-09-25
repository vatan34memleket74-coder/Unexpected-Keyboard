package juloo.keyboard2.suggestions;

import android.content.Context;
import android.os.Build.VERSION;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import juloo.keyboard2.Config;
import juloo.keyboard2.KeyValue;
import juloo.keyboard2.Pointers;
import juloo.keyboard2.R;
import juloo.keyboard2.ClipboardHistoryService;

public class CandidatesView extends LinearLayout
{
  static final int NUM_CANDIDATES = 4;

  /** Candidates currently visible. Entries can be [null] when there are less
      than [NUM_CANDIDATES] suggestions.
      - Entries at indexes [0] to [2] are word suggestions.
      - Entry at index [3] is the emoji suggestion. */
  String[] _items = new String[NUM_CANDIDATES];

  /** Text views showing the candidates in [_items]. Text views visibility is
      set to [GONE] when there are less than [NUM_CANDIDATES] suggestions. */
  TextView[] _item_views = new TextView[NUM_CANDIDATES];

  /** Message when no dictionary is installed. Visible when no candidates are
      shown. Might be [null]. */
  View _status_no_dict = null;

  View _clipboard_button;
  /** The most recently copied text, eligible for quick-paste suggestion. */
  String _recent_clip = null;
  /** Whether the recently copied text is eligible to appear as a suggestion.
      Set to true when a clipboard copy event occurs; set to false when the
      user starts typing or the recency timeout expires. */
  boolean _clipboard_suggestion_active = false;
  /** Whether the editor is currently idle (no word being composed).
      Updated from [Suggestions.is_idle] in [set_candidates]. */
  boolean _is_idle = true;
  /** Index in [_items] at which the clipboard text is currently shown, or -1
      if not currently shown as a suggestion. */
  int _clipboard_item_index = -1;
  /** Delay (in ms) after which the recently-copied text is no longer offered
      as a suggestion. */
  static final long CLIPBOARD_RECENT_TIMEOUT_MS = 15_000;
  Runnable _clipboard_timeout_run = new Runnable()
  {
    public void run()
    {
      clear_clipboard_suggestion();
    }
  };
  ClipboardHistoryService.OnClipboardHistoryChange _clipboard_listener = null;

  /** Type of each candidate item. */
  enum ItemType { NORMAL, CLIPBOARD, PASTE, UNDO, REDO }
  ItemType[] _item_types = new ItemType[NUM_CANDIDATES];

  /** Listener for clipboard button clicks. */
  public interface OnClipboardButtonClickListener
  {
    void onClipboardButtonClick();
  }
  private OnClipboardButtonClickListener _clipboard_button_click_listener = null;

  public void setOnClipboardButtonClickListener(OnClipboardButtonClickListener l)
  {
    _clipboard_button_click_listener = l;
  }

  public CandidatesView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate()
  {
    super.onFinishInflate();
    setup_item_view(0, R.id.candidates_middle);
    setup_item_view(1, R.id.candidates_right);
    setup_item_view(2, R.id.candidates_left);
    setup_item_view(3, R.id.candidates_emoji);
    setup_clipboard_button();
  }

  /** Paste text into the editor via the clipboard service. */
  void send_suggestion_text(String text)
  {
    ClipboardHistoryService.paste(text);
  }

  /** Clear any pending clipboard-suggestion timeout. */
  void cancel_clipboard_timeout()
  {
    removeCallbacks(_clipboard_timeout_run);
  }

  /** Disable and visually remove the temporary clipboard suggestion.
      Called when the user starts typing, the recency timeout expires, or
      the candidates view is cleared. Does NOT affect the permanent
      clipboard button visibility. */
  public void clear_clipboard_suggestion()
  {
    _clipboard_suggestion_active = false;
    if (_clipboard_item_index >= 0)
    {
      _items[_clipboard_item_index] = null;
      TextView v = _item_views[_clipboard_item_index];
      if (v != null) v.setVisibility(View.GONE);
    }
    _clipboard_item_index = -1;
    cancel_clipboard_timeout();
  }

  public void set_candidates(Suggestions s)
  {
    int s_count = s.count;
    _is_idle = s.is_idle;
    for (int i = 0; i < Suggestions.MAX_COUNT; i++)
    {
      _items[i] = (i < s_count) ? s.suggestions[i] : null;
      _item_types[i] = ItemType.NORMAL;
    }
    _items[3] = s.emoji_suggestion;
    _item_types[3] = ItemType.NORMAL;
    // Reset clipboard suggestion tracking at the start of every refresh.
    _clipboard_item_index = -1;
    // Hide the status message when showing candidates.
    if (s_count != 0 && _status_no_dict != null)
      _status_no_dict.setVisibility(View.GONE);
    // If no word suggestions and the editor is idle, show the recently copied
    // text as a quick-paste suggestion in the first available slot.
    // Also show Paste, Undo, Redo actions in remaining slots.
    if (s_count == 0 && _is_idle && _clipboard_suggestion_active && _recent_clip != null)
    {
      // First, add clipboard quick-paste
      for (int i = 0; i < Suggestions.MAX_COUNT; i++)
      {
        if (_items[i] == null)
        {
          _items[i] = _recent_clip;
          _item_types[i] = ItemType.CLIPBOARD;
          _clipboard_item_index = i;
          break;
        }
      }
      // Then add Paste, Undo, Redo in remaining slots
      String pasteLabel = getResources().getString(R.string.key_descr_paste);
      String undoLabel = getResources().getString(R.string.key_descr_undo);
      String redoLabel = getResources().getString(R.string.key_descr_redo);
      String[] actionLabels = { pasteLabel, undoLabel, redoLabel };
      ItemType[] actionTypes = { ItemType.PASTE, ItemType.UNDO, ItemType.REDO };
      int actionIdx = 0;
      for (int i = 0; i < Suggestions.MAX_COUNT && actionIdx < actionLabels.length; i++)
      {
        if (_items[i] == null)
        {
          _items[i] = actionLabels[actionIdx];
          _item_types[i] = actionTypes[actionIdx];
          actionIdx++;
        }
      }
    }
    for (int i = 0; i < _item_views.length; i++)
    {
      TextView v = _item_views[i];
      if (_items[i] != null)
      {
        v.setText(_items[i]);
        v.setVisibility(View.VISIBLE);
      }
      else
      {
        v.setVisibility(View.GONE);
      }
    }
    update_clipboard_button();
  }

  void clear_candidates()
  {
    clear_clipboard_suggestion();
    for (int i = 0; i < _item_views.length; i++)
    {
      _items[i] = null;
      _item_types[i] = ItemType.NORMAL;
      _item_views[i].setVisibility(View.GONE);
    }
  }

  public void refresh_config(Config config)
  {
    clear_candidates();
    // The status message indicates whether the dictionaries should be
    // installed.
    if (config.current_dictionary == null)
      inflate_status_no_dict(config);
    else if (_status_no_dict != null)
      _status_no_dict.setVisibility(View.GONE);
    set_sizes(config);
    update_clipboard_button();
  }

  /** Set the height of the suggestion row and the text size. */
  void set_sizes(Config config)
  {
    // Make the candidates view about as high as a keyboard row.
    float row_height = config.keyboard_rows_height_pixels * (1 - config.key_vertical_margin) * 0.9f;
    ViewGroup.MarginLayoutParams p =
      (ViewGroup.MarginLayoutParams)getLayoutParams();
    p.height = (int)row_height;
    setLayoutParams(p);
    // Match the size of labels on the keyboard.
    float text_size = row_height * config.characterSize * config.labelTextSize;
    for (int i = 0; i < NUM_CANDIDATES; i++)
    {
      TextView v = _item_views[i];
      // Set text size and enable auto size if supported.
      if (VERSION.SDK_INT < 26)
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, text_size);
      else
        v.setAutoSizeTextTypeUniformWithConfiguration(
            (int)(text_size / 2.), (int)text_size, 1, TypedValue.COMPLEX_UNIT_PX);
    }
  }

  void inflate_status_no_dict(Config config)
  {
    if (_status_no_dict == null)
    {
      _status_no_dict = View.inflate(getContext(),
          R.layout.candidates_status_no_dict, null);
      addView(_status_no_dict);
    }
    Locale current_locale = (config.device_locales.default_ != null) ?
      Locale.forLanguageTag(config.device_locales.default_.lang_tag) : null;
    TextView tv = _status_no_dict.findViewById(android.R.id.text1);
    if (tv != null && current_locale != null)
      tv.setText(getResources().getString(
            R.string.candidates_status_click_to_install,
            current_locale.getDisplayName()));
    _status_no_dict.setVisibility(View.VISIBLE);
  }

  private void setup_item_view(final int item_index, int item_id)
  {
    TextView v = (TextView)findViewById(item_id);
    v.setOnClickListener(new View.OnClickListener()
        {
          @Override
          public void onClick(View _v)
          {
            String it = _items[item_index];
            if (it == null) return;
            ItemType type = _item_types[item_index];
            if (type == ItemType.CLIPBOARD)
            {
              send_suggestion_text(it);
            }
            else if (type == ItemType.PASTE)
            {
              Config.globalConfig().handler.handle_editing_key(KeyValue.Editing.PASTE);
            }
            else if (type == ItemType.UNDO)
            {
              Config.globalConfig().handler.handle_editing_key(KeyValue.Editing.UNDO);
            }
            else if (type == ItemType.REDO)
            {
              Config.globalConfig().handler.handle_editing_key(KeyValue.Editing.REDO);
            }
            else
            {
              Config.globalConfig().handler.suggestion_entered(it);
            }
          }
        });
    v.setVisibility(View.GONE);
    _item_views[item_index] = v;
  }

  void setup_clipboard_button()
  {
    _clipboard_button = findViewById(R.id.candidates_clipboard_button);
    if (_clipboard_button != null)
      _clipboard_button.setOnClickListener(new View.OnClickListener()
          {
            @Override
            public void onClick(View _v)
            {
              if (_clipboard_button_click_listener != null)
                _clipboard_button_click_listener.onClipboardButtonClick();
            }
          });
    // Listen for clipboard changes to capture recently copied text.
    ClipboardHistoryService srv = ClipboardHistoryService.get_service(getContext());
    if (srv != null)
    {
      _clipboard_listener = new ClipboardHistoryService.OnClipboardHistoryChange()
      {
        @Override
        public void on_clipboard_history_change()
        {
          on_clipboard_changed();
        }
      };
      srv.set_on_clipboard_history_change(_clipboard_listener);
    }
    update_clipboard_button();
  }

  /** Called when the clipboard history changes. Captures the most recent copy
      as an eligible quick-paste suggestion and refreshes the UI. */
  void on_clipboard_changed()
  {
    ClipboardHistoryService srv = ClipboardHistoryService.get_service(getContext());
    if (srv == null) return;
    List<String> history = srv.clear_expired_and_get_history();
    if (history.isEmpty()) return;
    _recent_clip = history.get(0);
    _clipboard_suggestion_active = true;
    _clipboard_item_index = -1;
    cancel_clipboard_timeout();
    postDelayed(_clipboard_timeout_run, CLIPBOARD_RECENT_TIMEOUT_MS);
    // Only show the clipboard suggestion if the editor is currently idle.
    if (_is_idle)
      refresh_clipboard_suggestion();
  }

  /** Re-render the suggestion items to incorporate the clipboard suggestion
      state. Does not query the dictionary. */
  void refresh_clipboard_suggestion()
  {
    _clipboard_item_index = -1;
    if (_clipboard_suggestion_active && _recent_clip != null)
    {
      for (int i = 0; i < Suggestions.MAX_COUNT; i++)
      {
        if (_items[i] == null)
        {
          _items[i] = _recent_clip;
          _clipboard_item_index = i;
          TextView v = _item_views[i];
          if (v != null)
          {
            v.setText(_recent_clip);
            v.setVisibility(View.VISIBLE);
          }
          break;
        }
      }
    }
    update_clipboard_button_visibility();
  }

  /** Update the permanent clipboard button visibility and refresh the
      clipboard suggestion if active. */
  void update_clipboard_button()
  {
    update_clipboard_button_visibility();
    if (_clipboard_suggestion_active && _recent_clip != null)
      refresh_clipboard_suggestion();
  }

  /** Update only the permanent clipboard button visibility.
      The button is always visible when it exists; the click handler
      safely does nothing if clipboard history is empty. */
  void update_clipboard_button_visibility()
  {
    if (_clipboard_button == null) return;
    _clipboard_button.setVisibility(View.VISIBLE);
  }

  @Override
  protected void onDetachedFromWindow()
  {
    super.onDetachedFromWindow();
    cancel_clipboard_timeout();
    ClipboardHistoryService srv = ClipboardHistoryService.get_service(getContext());
    if (srv != null && _clipboard_listener != null)
      srv.set_on_clipboard_history_change(null);
  }

  /** Whether the candidates view should be shown for a given editor. */
  public static boolean should_show(EditorInfo info)
  {
    int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
    int flags = info.inputType & InputType.TYPE_MASK_FLAGS;
    switch (info.inputType & InputType.TYPE_MASK_CLASS)
    {
      case InputType.TYPE_CLASS_TEXT:
        switch (variation)
        {
          case InputType.TYPE_TEXT_VARIATION_PASSWORD:
          case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
          case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
            return false;
          default:
            /* Editor requested that we don't show suggestions. Enable
               suggestions anyway when the flags [NO_SUGGESTIONS] and
               [AUTO_CORRECT] are present at the same time. This happens with
               Google Keep. */
            if ((flags &
                  (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                   | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
                == InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
              return false;
            return true;
        }
      case InputType.TYPE_CLASS_NUMBER:
        // Beware of TYPE_NUMBER_VARIATION_PASSWORD
        return false;
      default: return false;
    }
  }
}
