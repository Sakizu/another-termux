package com.termux.app.terminal.io;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.termux.app.TermuxActivity;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

/**
 * Work around for fullscreen mode in Termux to fix ExtraKeysView not being visible.
 * This class is derived from:
 * https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible
 * and has some additional tweaks
 * ---
 * For more information, see https://issuetracker.google.com/issues/36911528
 */
public class FullScreenWorkAround {
    private final View mChildOfContent;
    private int mUsableHeightPrevious;
    private final ViewGroup.LayoutParams mViewGroupLayoutParams;
    private final Rect mRect = new Rect();
    private final int mOriginalHeight;

    private final TermuxActivity mActivity;
    private final TermuxAppSharedPreferences mPreferences;
    private final ViewTreeObserver.OnGlobalLayoutListener mLayoutListener;
    private final ViewTreeObserver mViewTreeObserver;


    /**
     * Apply the work around if it is needed (legacy fullscreen with the work
     * around enabled in termux.properties, or immersive mode): create and store
     * an instance when none is active, ask an existing instance to re-evaluate
     * when the geometry may have changed, or tear the instance down when the
     * work around is no longer needed. Safe to call any number of times.
     */
    public static void applyIfNeeded(TermuxActivity activity) {
        if (activity == null) return;

        TermuxAppSharedProperties properties = activity.getProperties();
        TermuxAppSharedPreferences preferences = activity.getPreferences();

        boolean needed = (properties != null && properties.isUsingFullScreen() && properties.isUsingFullScreenWorkAround())
            || (preferences != null && preferences.isImmersiveModeEnabled());

        FullScreenWorkAround instance = activity.getFullScreenWorkAround();

        if (needed) {
            if (instance != null) {
                // The geometry may have changed while the work around was
                // active (e.g. immersive mode was toggled with the keyboard
                // up), so force the resize check to re-evaluate instead of
                // waiting for the next layout pass.
                instance.recheck();
            } else {
                // The content view may not exist yet when this is called early
                // (e.g. from setImmersiveMode()); the toolbar pager calls this
                // again later once the layout is ready, so do nothing for now.
                ViewGroup content = activity.findViewById(android.R.id.content);
                if (content == null || content.getChildAt(0) == null) return;
                activity.setFullScreenWorkAround(new FullScreenWorkAround(activity));
            }
        } else if (instance != null) {
            instance.deactivate();
            activity.setFullScreenWorkAround(null);
        }
    }

    private FullScreenWorkAround(TermuxActivity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        mChildOfContent = content.getChildAt(0);
        mViewGroupLayoutParams = mChildOfContent.getLayoutParams();
        mOriginalHeight = mViewGroupLayoutParams.height;
        // Do not cache the nav bar height here: this can run before insets
        // are dispatched (0 forever). It is read lazily in getNavBarHeight().
        mActivity = activity;
        mPreferences = activity.getPreferences();
        mLayoutListener = this::possiblyResizeChildOfContent;
        mViewTreeObserver = mChildOfContent.getViewTreeObserver();
        mViewTreeObserver.addOnGlobalLayoutListener(mLayoutListener);
    }

    /**
     * Force the resize check to run immediately, even if the usable height has
     * not changed since the last evaluation.
     */
    public void recheck() {
        // -1 is not a possible usable height, so the next check always runs.
        mUsableHeightPrevious = -1;
        // Do not evaluate synchronously before the first layout: the view has
        // no size yet and the check would compute a garbage height. The pending
        // first layout pass drives the check through the listener instead.
        if (mChildOfContent.isLaidOut()) {
            possiblyResizeChildOfContent();
        }
    }

    /**
     * Remove the work around and restore the content view to its original size.
     */
    public void deactivate() {
        // Removal goes through the cached observer, so it works even if the
        // view has since been detached (which would return a dead observer).
        mViewTreeObserver.removeOnGlobalLayoutListener(mLayoutListener);
        mViewGroupLayoutParams.height = mOriginalHeight;
        mChildOfContent.requestLayout();
    }

    private void possiblyResizeChildOfContent() {
        int usableHeightNow = computeUsableHeight();
        if (usableHeightNow != mUsableHeightPrevious) {
            int usableHeightSansKeyboard = mChildOfContent.getRootView().getHeight();
            int heightDifference = usableHeightSansKeyboard - usableHeightNow;
            if (heightDifference > (usableHeightSansKeyboard / 4)) {
                // keyboard probably just became visible

                // ensures that usable layout space does not extend behind the
                // soft keyboard, causing the extra keys to not be visible
                mViewGroupLayoutParams.height = (usableHeightSansKeyboard - heightDifference) + getNavBarHeight();
            } else {
                // keyboard probably just became hidden
                mViewGroupLayoutParams.height = usableHeightSansKeyboard;
            }
            mChildOfContent.requestLayout();
            mUsableHeightPrevious = usableHeightNow;
        }
    }

    private int getNavBarHeight() {
        // Immersive mode hides the navigation bar itself, so compensating for
        // it would make the content taller than the visible area and leave the
        // extra keys stuck behind the keyboard. The legacy fullscreen path
        // keeps the original compensation and is unaffected.
        if (mPreferences != null && mPreferences.isImmersiveModeEnabled()) {
            return 0;
        }
        // Read lazily: the value is only valid once insets have been
        // dispatched, which may be after this object was constructed.
        return mActivity.getNavBarHeight();
    }

    private int computeUsableHeight() {
        mChildOfContent.getWindowVisibleDisplayFrame(mRect);
        return (mRect.bottom - mRect.top);
    }

}
