package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.theme.NightMode;
import com.termux.shared.theme.ThemeUtils;
import com.termux.terminal.TerminalSession;

import java.util.Collections;
import java.util.List;

/**
 * RecyclerView adapter backing the session drawer list.
 *
 * Rows are reordered with drag handles (ItemTouchHelper), sessions are renamed
 * inline through the row EditText, and tapping a row switches to that session.
 * The adapter holds the live TermuxService session list, so a reorder is
 * immediately reflected in session numbering and switching.
 */
public class TermuxSessionsListViewController extends RecyclerView.Adapter<TermuxSessionsListViewController.SessionViewHolder> {

    final TermuxActivity mActivity;
    final List<TermuxSession> mSessionList;

    final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

    ItemTouchHelper mItemTouchHelper;

    /** Adapter position currently showing the inline rename field, or -1. */
    int mRenamingPosition = -1;

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        this.mActivity = activity;
        this.mSessionList = sessionList;
    }

    /** Attach drag-to-reorder handling to the drawer RecyclerView. */
    public void attachToRecyclerView(RecyclerView recyclerView) {
        mItemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int fromPosition = viewHolder.getBindingAdapterPosition();
                int toPosition = target.getBindingAdapterPosition();
                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION)
                    return false;
                if (mSessionList == null || fromPosition >= mSessionList.size() || toPosition >= mSessionList.size())
                    return false;
                // Reorder the live service list so numbering and switching follow.
                Collections.swap(mSessionList, fromPosition, toPosition);
                mRenamingPosition = -1;
                notifyItemMoved(fromPosition, toPosition);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Swiping is disabled.
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // Drag is started from the drag handle only, not the whole row.
                return false;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }
        });
        mItemTouchHelper.attachToRecyclerView(recyclerView);
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View rowView = mActivity.getLayoutInflater().inflate(R.layout.item_terminal_sessions_list, parent, false);
        SessionViewHolder holder = new SessionViewHolder(rowView);

        // The drag handle is the only drag source: a press starts the drag.
        holder.dragHandle.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN && mItemTouchHelper != null) {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION)
                    mItemTouchHelper.startDrag(holder);
            }
            // Consume the touch so the row click does not fire.
            return true;
        });

        // Row tap switches to the session and closes the drawer.
        holder.itemView.setOnClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            TermuxSession clickedSession = getSessionAt(position);
            if (clickedSession == null || clickedSession.getTerminalSession() == null) return;
            mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
            mActivity.getDrawer().closeDrawers();
        });

        // Long-press keeps the rename dialog as a fallback.
        holder.itemView.setOnLongClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return true;
            TermuxSession selectedSession = getSessionAt(position);
            if (selectedSession == null || selectedSession.getTerminalSession() == null) return true;
            mActivity.getTermuxTerminalSessionClient().renameSession(selectedSession.getTerminalSession());
            return true;
        });

        // Tapping the label or the pencil starts the inline rename.
        View.OnClickListener renameClickListener = v -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            startInlineRename(holder, position);
        };
        holder.titleView.setOnClickListener(renameClickListener);
        holder.renameButton.setOnClickListener(renameClickListener);
        // Long-press on the pencil must not bubble up to the row's long-press
        // (which opens the rename dialog) — it should also start inline rename.
        holder.renameButton.setOnLongClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return true;
            startInlineRename(holder, position);
            return true;
        });

        // IME Done / Enter commits, back cancels, an empty name cancels.
        holder.renameField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitInlineRename(holder);
                return true;
            }
            return false;
        });
        holder.renameField.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                commitInlineRename(holder);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                cancelInlineRename(holder);
                return true;
            }
            return false;
        });

        return holder;
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        TermuxSession termuxSession = getSessionAt(position);
        TerminalSession sessionAtRow = termuxSession == null ? null : termuxSession.getTerminalSession();
        if (sessionAtRow == null) {
            holder.titleView.setText("null session");
            holder.titleView.setVisibility(View.VISIBLE);
            holder.renameField.setVisibility(View.GONE);
            holder.activeMarker.setVisibility(View.INVISIBLE);
            holder.itemView.setActivated(false);
            return;
        }

        if (position == mRenamingPosition) {
            showRenameField(holder, sessionAtRow);
        } else {
            showTitleLabel(holder, sessionAtRow, position);
        }

        // Row background follows the theme, like the stock drawer did.
        boolean shouldEnableDarkTheme = ThemeUtils.shouldEnableDarkTheme(mActivity, NightMode.getAppNightMode().getName());
        holder.itemView.setBackground(ContextCompat.getDrawable(mActivity,
            shouldEnableDarkTheme ? R.drawable.session_background_black_selected : R.drawable.session_background_selected));

        // Active row marker: white left border in dark theme, black in light theme.
        TerminalSession currentSession = mActivity.getCurrentSession();
        boolean isCurrentSession = currentSession != null && currentSession == sessionAtRow;
        holder.itemView.setActivated(isCurrentSession);
        holder.activeMarker.setVisibility(isCurrentSession ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public int getItemCount() {
        return mSessionList == null ? 0 : mSessionList.size();
    }

    private TermuxSession getSessionAt(int position) {
        if (mSessionList == null || position < 0 || position >= mSessionList.size())
            return null;
        return mSessionList.get(position);
    }

    @SuppressLint("SetTextI18n")
    private void showTitleLabel(@NonNull SessionViewHolder holder, @NonNull TerminalSession sessionAtRow, int position) {
        holder.renameField.setVisibility(View.GONE);
        holder.titleView.setVisibility(View.VISIBLE);
        TextView sessionTitleView = holder.titleView;

        boolean shouldEnableDarkTheme = ThemeUtils.shouldEnableDarkTheme(mActivity, NightMode.getAppNightMode().getName());

        String name = sessionAtRow.mSessionName;
        String sessionTitle = sessionAtRow.getTitle();

        String numberPart = "[" + (position + 1) + "] ";
        String sessionNamePart = (TextUtils.isEmpty(name) ? "" : name);
        String sessionTitlePart = (TextUtils.isEmpty(sessionTitle) ? "" : ((sessionNamePart.isEmpty() ? "" : "\n") + sessionTitle));

        String fullSessionTitle = numberPart + sessionNamePart + sessionTitlePart;
        SpannableString fullSessionTitleStyled = new SpannableString(fullSessionTitle);
        fullSessionTitleStyled.setSpan(boldSpan, 0, numberPart.length() + sessionNamePart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        fullSessionTitleStyled.setSpan(italicSpan, numberPart.length() + sessionNamePart.length(), fullSessionTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sessionTitleView.setTypeface(Typeface.MONOSPACE);
        sessionTitleView.setText(fullSessionTitleStyled);

        boolean sessionRunning = sessionAtRow.isRunning();

        if (sessionRunning) {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        int defaultColor = shouldEnableDarkTheme ? Color.WHITE : Color.BLACK;
        int color = sessionRunning || sessionAtRow.getExitStatus() == 0 ? defaultColor : Color.RED;
        sessionTitleView.setTextColor(color);
    }

    private void showRenameField(@NonNull SessionViewHolder holder, @NonNull TerminalSession sessionAtRow) {
        holder.titleView.setVisibility(View.GONE);
        holder.renameField.setVisibility(View.VISIBLE);
        holder.renameField.setText(sessionAtRow.mSessionName);
        holder.renameField.selectAll();
        // Post the focus + keyboard request: calling showSoftInput synchronously here
        // is unreliable because the view may not be laid out/focused yet, so the
        // keyboard sometimes never appears.
        holder.renameField.post(() -> {
            holder.renameField.requestFocus();
            InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.showSoftInput(holder.renameField, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void startInlineRename(@NonNull SessionViewHolder holder, int position) {
        TermuxSession termuxSession = getSessionAt(position);
        TerminalSession sessionAtRow = termuxSession == null ? null : termuxSession.getTerminalSession();
        if (sessionAtRow == null) return;
        mRenamingPosition = position;
        showRenameField(holder, sessionAtRow);
    }

    private void commitInlineRename(@NonNull SessionViewHolder holder) {
        int position = holder.getBindingAdapterPosition();
        TermuxSession termuxSession = position == RecyclerView.NO_POSITION ? null : getSessionAt(position);
        TerminalSession sessionAtRow = termuxSession == null ? null : termuxSession.getTerminalSession();
        String newName = holder.renameField.getText().toString().trim();
        hideRenameField(holder);
        mRenamingPosition = -1;
        if (sessionAtRow != null && !newName.isEmpty())
            mActivity.getTermuxTerminalSessionClient().renameSessionToName(sessionAtRow, newName);
        if (position != RecyclerView.NO_POSITION)
            notifyItemChanged(position);
    }

    private void cancelInlineRename(@NonNull SessionViewHolder holder) {
        int position = holder.getBindingAdapterPosition();
        hideRenameField(holder);
        mRenamingPosition = -1;
        if (position != RecyclerView.NO_POSITION)
            notifyItemChanged(position);
    }

    private void hideRenameField(@NonNull SessionViewHolder holder) {
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null)
            imm.hideSoftInputFromWindow(holder.renameField.getWindowToken(), 0);
        holder.renameField.clearFocus();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        final View activeMarker;
        final ImageView dragHandle;
        final TextView titleView;
        final EditText renameField;
        final ImageButton renameButton;

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            activeMarker = itemView.findViewById(R.id.session_active_marker);
            dragHandle = itemView.findViewById(R.id.session_drag_handle);
            titleView = itemView.findViewById(R.id.session_title);
            renameField = itemView.findViewById(R.id.session_rename_field);
            renameButton = itemView.findViewById(R.id.session_rename_button);
        }
    }

}
