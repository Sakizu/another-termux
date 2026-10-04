package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
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
 * Rows are reordered with drag handles (ItemTouchHelper), the ⋮ menu offers
 * Rename (stock dialog) and Kill session, and tapping a row switches to that session.
 * The adapter holds the live TermuxService session list, so a reorder is
 * immediately reflected in session numbering and switching.
 */
public class TermuxSessionsListViewController extends RecyclerView.Adapter<TermuxSessionsListViewController.SessionViewHolder> {

    final TermuxActivity mActivity;
    final List<TermuxSession> mSessionList;

    final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

    ItemTouchHelper mItemTouchHelper;

    /** Cached ConstantStates for the session row backgrounds, re-resolved only
     *  when the theme changes. The backgrounds are state_activated selectors,
     *  so each row still gets its own Drawable instance via newDrawable() —
     *  sharing a single instance across rows would route state changes and
     *  invalidation to the wrong row. All access is on the main thread. */
    private Drawable.ConstantState mRowBackgroundDarkState;
    private Drawable.ConstantState mRowBackgroundLightState;
    private boolean mRowBackgroundsCachedThemeDark;
    private boolean mRowBackgroundsCached;

    private static final int MENU_RENAME_ID = 1;
    private static final int MENU_KILL_ID = 2;

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
        // (The label has no own click listener on purpose so taps anywhere
        // on the row switch the session; rename lives in the ⋮ menu.)
        holder.itemView.setOnClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            TermuxSession clickedSession = getSessionAt(position);
            if (clickedSession == null || clickedSession.getTerminalSession() == null) return;
            mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
            mActivity.getDrawer().closeDrawers();
        });

        // The ⋮ button opens the session menu (Rename / Kill session).
        holder.menuButton.setOnClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            showSessionMenu(holder, position, v);
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
            holder.activeMarker.setVisibility(View.INVISIBLE);
            holder.itemView.setActivated(false);
            // Reset any recycled styling (strikethrough, text color, row
            // background) so the placeholder row does not inherit it.
            holder.titleView.setPaintFlags(holder.titleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            boolean darkTheme = ThemeUtils.shouldEnableDarkTheme(mActivity, NightMode.getAppNightMode().getName());
            holder.titleView.setTextColor(darkTheme ? Color.WHITE : Color.BLACK);
            holder.itemView.setBackground(getSessionRowBackground(darkTheme));
            return;
        }

        // Resolve the theme once per bind and share it with showTitleLabel below.
        boolean shouldEnableDarkTheme = ThemeUtils.shouldEnableDarkTheme(mActivity, NightMode.getAppNightMode().getName());

        showTitleLabel(holder, sessionAtRow, shouldEnableDarkTheme);

        // Row background follows the theme, like the stock drawer did.
        holder.itemView.setBackground(getSessionRowBackground(shouldEnableDarkTheme));

        // Active row marker: white left border in dark theme, black in light theme.
        TerminalSession currentSession = mActivity.getCurrentSession();
        boolean isCurrentSession = currentSession != null && currentSession == sessionAtRow;
        holder.itemView.setActivated(isCurrentSession);
        holder.activeMarker.setVisibility(isCurrentSession ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void onViewRecycled(@NonNull SessionViewHolder holder) {
        // A menu left open on a recycled row would float over the wrong
        // session, so dismiss it with the row.
        if (holder.popup != null) {
            holder.popup.dismiss();
            holder.popup = null;
        }
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return mSessionList == null ? 0 : mSessionList.size();
    }

    /** Adapter position of the row showing the given session, or -1. */
    private int indexOfSession(@NonNull TerminalSession session) {
        if (mSessionList == null) return -1;
        for (int i = 0; i < mSessionList.size(); i++) {
            TermuxSession termuxSession = mSessionList.get(i);
            if (termuxSession != null && termuxSession.getTerminalSession() == session)
                return i;
        }
        return -1;
    }

    /** Refresh only the row for the given session (e.g. on title change). */
    public void notifySessionChanged(@NonNull TerminalSession session) {
        int index = indexOfSession(session);
        if (index >= 0)
            notifyItemChanged(index);
    }

    /** Animate in the row for a newly added session. */
    public void notifySessionInserted(int position) {
        // The row was already added to the list, so the last valid position is
        // getItemCount() - 1 (unlike notifySessionRemoved below, where the list
        // was already decremented).
        if (position >= 0 && position < getItemCount())
            notifyItemInserted(position);
        else
            notifyDataSetChanged();
    }

    /** Animate out the row for a removed session. */
    public void notifySessionRemoved(int position) {
        // getItemCount() is already decremented, so the removed row was at
        // most at getItemCount().
        if (position >= 0 && position <= getItemCount())
            notifyItemRemoved(position);
        else
            notifyDataSetChanged();
    }

    private TermuxSession getSessionAt(int position) {
        if (mSessionList == null || position < 0 || position >= mSessionList.size())
            return null;
        return mSessionList.get(position);
    }

    /** Row background drawable for the given theme. Minting a per-row instance
     *  from the cached ConstantState avoids XML re-inflation on every bind. */
    private Drawable getSessionRowBackground(boolean darkTheme) {
        if (!mRowBackgroundsCached || mRowBackgroundsCachedThemeDark != darkTheme) {
            Drawable dark = ContextCompat.getDrawable(mActivity, R.drawable.session_background_black_selected);
            Drawable light = ContextCompat.getDrawable(mActivity, R.drawable.session_background_selected);
            mRowBackgroundDarkState = dark == null ? null : dark.getConstantState();
            mRowBackgroundLightState = light == null ? null : light.getConstantState();
            mRowBackgroundsCachedThemeDark = darkTheme;
            mRowBackgroundsCached = true;
        }
        Drawable.ConstantState state = darkTheme ? mRowBackgroundDarkState : mRowBackgroundLightState;
        return state == null ? null : state.newDrawable(mActivity.getResources());
    }

    @SuppressLint("SetTextI18n")
    private void showTitleLabel(@NonNull SessionViewHolder holder, @NonNull TerminalSession sessionAtRow, boolean darkTheme) {
        holder.titleView.setVisibility(View.VISIBLE);
        TextView sessionTitleView = holder.titleView;

        String name = sessionAtRow.mSessionName;
        String sessionTitle = sessionAtRow.getTitle();

        // Display-only fallback: a fresh session may have no name and no
        // terminal title yet; show a placeholder instead of a blank row.
        // The placeholder is never stored as the session name.
        if (TextUtils.isEmpty(name) && TextUtils.isEmpty(sessionTitle))
            name = mActivity.getString(R.string.label_new_session);

        // No session numbering in the drawer (user request) — just name + title.
        String sessionNamePart = (TextUtils.isEmpty(name) ? "" : name);
        String sessionTitlePart = (TextUtils.isEmpty(sessionTitle) ? "" : ((sessionNamePart.isEmpty() ? "" : "\n") + sessionTitle));

        String fullSessionTitle = sessionNamePart + sessionTitlePart;
        SpannableString fullSessionTitleStyled = new SpannableString(fullSessionTitle);
        fullSessionTitleStyled.setSpan(boldSpan, 0, sessionNamePart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        fullSessionTitleStyled.setSpan(italicSpan, sessionNamePart.length(), fullSessionTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sessionTitleView.setText(fullSessionTitleStyled);

        boolean sessionRunning = sessionAtRow.isRunning();

        if (sessionRunning) {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        int defaultColor = darkTheme ? Color.WHITE : Color.BLACK;
        int color = sessionRunning || sessionAtRow.getExitStatus() == 0 ? defaultColor : Color.RED;
        sessionTitleView.setTextColor(color);
    }

    /** Session ⋮ menu: Rename (dialog) and Kill session (red, with confirmation). */
    private void showSessionMenu(@NonNull SessionViewHolder holder, int position, @NonNull View anchor) {
        if (getSessionAt(position) == null) return;

        // Only one menu per row at a time; dismissed again on recycle.
        if (holder.popup != null) {
            holder.popup.dismiss();
        }
        PopupMenu popup = new PopupMenu(mActivity, anchor);
        holder.popup = popup;
        popup.getMenu().add(Menu.NONE, MENU_RENAME_ID, Menu.NONE, R.string.action_rename_session);
        SpannableString killTitle = new SpannableString(mActivity.getString(R.string.action_kill_session));
        killTitle.setSpan(new ForegroundColorSpan(Color.RED), 0, killTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        popup.getMenu().add(Menu.NONE, MENU_KILL_ID, Menu.NONE, killTitle);
        popup.setOnMenuItemClickListener(item -> {
            int currentPosition = holder.getBindingAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION) return false;
            int itemId = item.getItemId();
            if (itemId == MENU_RENAME_ID) {
                // Stock rename dialog (reliable keyboard); inline rename was
                // removed — the drawer EditText could not grab IME input.
                TermuxSession termuxSession = getSessionAt(currentPosition);
                TerminalSession terminalSession = termuxSession == null ? null : termuxSession.getTerminalSession();
                if (terminalSession != null)
                    mActivity.getTermuxTerminalSessionClient().renameSession(terminalSession);
                return true;
            } else if (itemId == MENU_KILL_ID) {
                confirmKillSession(currentPosition);
                return true;
            }
            return false;
        });
        popup.show();
    }

    /** Confirm then kill (remove) the session. */
    private void confirmKillSession(int position) {
        TermuxSession termuxSession = getSessionAt(position);
        TerminalSession sessionAtRow = termuxSession == null ? null : termuxSession.getTerminalSession();
        if (sessionAtRow == null) return;

        new AlertDialog.Builder(mActivity)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(R.string.title_confirm_kill_process)
            .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                dialog.dismiss();
                // SIGKILL the shell like the stock kill; the client removes
                // the drawer row once the session exits.
                mActivity.getTermuxTerminalSessionClient().requestKillSession(sessionAtRow);
            })
            .setNegativeButton(android.R.string.no, null)
            .show();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        final View activeMarker;
        final ImageView dragHandle;
        final TextView titleView;
        final ImageButton menuButton;
        /** The currently open ⋮ menu for this row, if any. */
        PopupMenu popup;

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            activeMarker = itemView.findViewById(R.id.session_active_marker);
            dragHandle = itemView.findViewById(R.id.session_drag_handle);
            titleView = itemView.findViewById(R.id.session_title);
            menuButton = itemView.findViewById(R.id.session_menu_button);
        }
    }

}
