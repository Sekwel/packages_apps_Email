/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.activity;

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Loader;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextPaint;
import android.util.Log;
import android.view.ActionMode;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.DragShadowBuilder;
import android.view.View.OnDragListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.MessageListContext;
import com.android.email.NotificationController;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.email.activity.MessagesAdapter.SearchResultsCursor;
import com.android.email.provider.EmailProvider;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

import java.util.Set;

/**
 * Message list.
 *
 * See the class javadoc for {@link MailboxListFragment} for notes on {@link #getListView()} and
 * {@link #isViewCreated()}.
 */
public class MessageListFragment extends ListFragment
        implements OnItemClickListener, OnItemLongClickListener, MessagesAdapter.Callback,
        MoveMessageToDialog.Callback, OnDragListener, OnTouchListener {
    private static final String BUNDLE_LIST_STATE = "MessageListFragment.state.listState";
    private static final String BUNDLE_KEY_SELECTED_MESSAGE_ID
            = "messageListFragment.state.listState.selected_message_id";

    private static final int LOADER_ID_MESSAGES_LOADER = 1;

    /** Argument name(s) */
    private static final String ARG_LIST_CONTEXT = "listContext";

    // Controller access
    private Controller mController;
    private RefreshManager mRefreshManager;
    private final RefreshListener mRefreshListener = new RefreshListener();

    // UI Support
    private Activity mActivity;
    private Callback mCallback = EmptyCallback.INSTANCE;
    private boolean mIsViewCreated;

    private View mListFooterView;
    private TextView mListFooterText;
    private View mListFooterProgress;
    private View mListPanel;
    private View mNoMessagesPanel;
    private ViewGroup mSearchHeader;
    private TextView mSearchHeaderText;
    private TextView mSearchHeaderCount;

    private static final int LIST_FOOTER_MODE_NONE = 0;
    private static final int LIST_FOOTER_MODE_MORE = 1;
    private int mListFooterMode;

    private MessagesAdapter mListAdapter;

    /** ID of the message to hightlight. */
    private long mSelectedMessageId = -1;

    private Account mAccount;
    private Mailbox mMailbox;
    /** The original mailbox being searched, if this list is showing search results. */
    private Mailbox mSearchedMailbox;
    private boolean mIsEasAccount;
    private boolean mIsRefreshable;
    private int mCountTotalAccounts;

    // Misc members

    /** Whether "Send all messages" should be shown. */
    private boolean mShowSendCommand;

    /**
     * If true, we disable the CAB even if there are selected messages.
     * It's used in portrait on the tablet when the message view becomes visible and the message
     * list gets pushed out of the screen, in which case we want to keep the selection but the CAB
     * should be gone.
     */
    private boolean mDisableCab;

    /** true between {@link #onResume} and {@link #onPause}. */
    private boolean mResumed;

    /**
     * {@link ActionMode} shown when 1 or more message is selected.
     */
    private ActionMode mSelectionMode;
    private SelectionModeCallback mLastSelectionModeCallback;

    private Parcelable mSavedListState;

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    /**
     * Callback interface that owning activities must implement
     */
    public interface Callback {
        public static final int TYPE_REGULAR = 0;
        public static final int TYPE_DRAFT = 1;
        public static final int TYPE_TRASH = 2;

        /** Called when a mailbox list is loaded.  */
        public void onListLoaded();

        /**
         * Called when the specified mailbox does not exist.
         */
        public void onMailboxNotFound();

        /**
         * Called when the user wants to open a message.
         * Note {@code mailboxId} is of the actual mailbox of the message, which is different from
         * {@link MessageListFragment#getMailboxId} if it's magic mailboxes.
         *
         * @param messageId the message ID of the message
         * @param messageMailboxId the mailbox ID of the message.
         *     This will never take values like {@link Mailbox#QUERY_ALL_INBOXES}.
         * @param listMailboxId the mailbox ID of the listbox shown on this fragment.
         *     This can be that of a magic mailbox, e.g.  {@link Mailbox#QUERY_ALL_INBOXES}.
         * @param type {@link #TYPE_REGULAR}, {@link #TYPE_DRAFT} or {@link #TYPE_TRASH}.
         */
        public void onMessageOpen(long messageId, long messageMailboxId, long listMailboxId,
                int type);

        /**
         * Called when entering/leaving selection mode.
         * @param enter true if entering, false if leaving
         */
        public void onEnterSelectionMode(boolean enter);

        /**
         * Called when an operation is initiated that can potentially advance the current
         * message selection (e.g. a delete operation may advance the selection).
         * @param affectedMessages the messages the operation will apply to
         */
        public void onAdvancingOpAccepted(Set<Long> affectedMessages);

        /**
         * Called when a drag & drop is initiated.
         *
         * @return true if drag & drop is allowed
         */
        public boolean onDragStarted();

        /**
         * Called when a drag & drop is ended.
         */
        public void onDragEnded();
    }

    private static final class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();

        @Override
        public void onListLoaded() {
        }

        @Override
        public void onMailboxNotFound() {
        }
        @Override
        public void onMessageOpen(
                long messageId, long messageMailboxId, long listMailboxId, int type) {
        }
        @Override
        public void onEnterSelectionMode(boolean enter) {
        }

        @Override
        public void onAdvancingOpAccepted(Set<Long> affectedMessages) {
        }

        @Override
        public boolean onDragStarted() {
            return false; // We don't know -- err on the safe side.
        }

        @Override
        public void onDragEnded() {
        }
    }

    /**
     * Create a new instance with initialization parameters.
     *
     * This fragment should be created only with this method.  (Arguments should always be set.)
     *
     * @param listContext The list context to show messages for
     */
    public static MessageListFragment newInstance(MessageListContext listContext) {
        final MessageListFragment instance = new MessageListFragment();
        final Bundle args = new Bundle();
        args.putParcelable(ARG_LIST_CONTEXT, listContext);
        instance.setArguments(args);
        return instance;
    }

    /**
     * The context describing the contents to be shown in the list.
     * Do not use directly; instead, use the getters such as {@link #getAccountId()}.
     * <p><em>NOTE:</em> Although we cannot force these to be immutable using Java language
     * constructs, this <em>must</em> be considered immutable.
     */
    private MessageListContext mListContext;

    private void initializeArgCache() {
        if (mListContext != null) return;
        mListContext = getArguments().getParcelable(ARG_LIST_CONTEXT);
    }

    /**
     * @return the account ID passed to {@link #newInstance}.  Safe to call even before onCreate.
     *
     * NOTE it may return {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     */
    public long getAccountId() {
        initializeArgCache();
        return mListContext.mAccountId;
    }

    /**
     * @return the mailbox ID passed to {@link #newInstance}.  Safe to call even before onCreate.
     */
    public long getMailboxId() {
        initializeArgCache();
        return mListContext.getMailboxId();
    }

    /**
     * @return true if the mailbox is a combined mailbox.  Safe to call even before onCreate.
     */
    public boolean isCombinedMailbox() {
        return getMailboxId() < 0;
    }

    public MessageListContext getListContext() {
        initializeArgCache();
        return mListContext;
    }

    /**
     * @return Whether or not initial data is loaded in this list.
     */
    public boolean hasDataLoaded() {
        return mCountTotalAccounts > 0;
    }

    /**
     * @return The account object, when known. Null if not yet known.
     */
    public Account getAccount() {
        return mAccount;
    }

    /**
     * @return The mailbox where the messages belong in, when known. Null if not yet known.
     */
    public Mailbox getMailbox() {
        return mMailbox;
    }

    /**
     * @return The mailbox being searched, when known. Null if not yet known or if not a search
     *    result.
     */
    public Mailbox getSearchedMailbox() {
        return mSearchedMailbox;
    }

    @Override
    public void onAttach(Activity activity) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onAttach");
        }
        super.onAttach(activity);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onCreate");
        }
        super.onCreate(savedInstanceState);

        mActivity = getActivity();
        setHasOptionsMenu(true);
        mController = Controller.getInstance(mActivity);
        mRefreshManager = RefreshManager.getInstance(mActivity);

        mListAdapter = new MessagesAdapter(mActivity, this);
        setListAdapter(mListAdapter);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onCreateView");
        }
        // Use a custom layout, which includes the original layout with "send messages" panel.
        View root = inflater.inflate(R.layout.message_list_fragment,null);
        mListPanel = UiUtilities.getView(root, R.id.list_panel);
        mNoMessagesPanel = UiUtilities.getView(root, R.id.no_messages_panel);
        mSearchHeader = UiUtilities.getView(root, R.id.search_header);
        mSearchHeaderText = UiUtilities.getView(mSearchHeader, R.id.search_header_text);
        mSearchHeaderCount = UiUtilities.getView(mSearchHeader, R.id.search_count);
        mIsViewCreated = true;
        return root;
    }

    /**
     * @return true if the content view is created and not destroyed yet. (i.e. between
     * {@link #onCreateView} and {@link #onDestroyView}.
     */
    private boolean isViewCreated() {
        // Note that we don't use "getView() != null".  This method is used in updateSelectionMode()
        // to determine if CAB shold be shown.  But because it's called from onDestroyView(), at
        // this point the fragment still has views but we want to hide CAB, we can't use
        // getView() here.
        return mIsViewCreated;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);

        final ListView lv = getListView();
        lv.setOnItemClickListener(this);
        lv.setOnItemLongClickListener(this);
        lv.setOnTouchListener(this);
        lv.setItemsCanFocus(false);
        lv.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        mListFooterView = getActivity().getLayoutInflater().inflate(
                R.layout.message_list_item_footer, lv, false);

        if (savedInstanceState != null) {
            // Fragment doesn't have this method.  Call it manually.
            restoreInstanceState(savedInstanceState);
        }

        startLoading();

        UiUtilities.installFragment(this);
    }

    @Override
    public void onStart() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onStart");
        }
        super.onStart();
    }

    @Override
    public void onResume() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onResume");
        }
        super.onResume();
        adjustMessageNotification(false);
        mRefreshManager.registerListener(mRefreshListener);
        mResumed = true;
    }

    @Override
    public void onPause() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onPause");
        }
        mResumed = false;
        mSavedListState = getListView().onSaveInstanceState();
        adjustMessageNotification(true);
        super.onPause();
    }

    @Override
    public void onStop() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onStop");
        }
        mTaskTracker.cancellAllInterrupt();
        mRefreshManager.unregisterListener(mRefreshListener);

        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onDestroyView");
        }
        mIsViewCreated = false; // Clear this first for updateSelectionMode(). See isViewCreated().
        UiUtilities.uninstallFragment(this);
        updateSelectionMode();
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onDestroy");
        }

        finishSelectionMode();
        super.onDestroy();
    }

    @Override
    public void onDetach() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onDetach");
        }
        super.onDetach();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        mListAdapter.onSaveInstanceState(outState);
        if (isViewCreated()) {
            outState.putParcelable(BUNDLE_LIST_STATE, getListView().onSaveInstanceState());
        }
        outState.putLong(BUNDLE_KEY_SELECTED_MESSAGE_ID, mSelectedMessageId);
    }

    @VisibleForTesting
    void restoreInstanceState(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " restoreInstanceState");
        }
        mListAdapter.loadState(savedInstanceState);
        mSavedListState = savedInstanceState.getParcelable(BUNDLE_LIST_STATE);
        mSelectedMessageId = savedInstanceState.getLong(BUNDLE_KEY_SELECTED_MESSAGE_ID);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.message_list_fragment_option, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.send).setVisible(mShowSendCommand);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.send:
                onSendPendingMessages();
                return true;

        }
        return false;
    }

    public void setCallback(Callback callback) {
        mCallback = (callback != null) ? callback : EmptyCallback.INSTANCE;
    }

    /**
     * This method must be called when the fragment is hidden/shown.
     */
    public void onHidden(boolean hidden) {
        // When hidden, we need to disable CAB.
        if (hidden == mDisableCab) {
            return;
        }
        mDisableCab = hidden;
        updateSelectionMode();
    }

    public void setSelectedMessage(long messageId) {
        if (mSelectedMessageId == messageId) {
            return;
        }
        mSelectedMessageId = messageId;
        if (mResumed) {
            highlightSelectedMessage(true);
        }
    }

    /* package */MessagesAdapter getAdapterForTest() {
        return mListAdapter;
    }

    /**
     * @return true if the mailbox is refreshable.  false otherwise, or unknown yet.
     */
    public boolean isRefreshable() {
        return mIsRefreshable;
    }

    /**
     * @return the number of messages that are currently selected.
     */
    private int getSelectedCount() {
        return mListAdapter.getSelectedSet().size();
    }

    /**
     * @return true if the list is in the "selection" mode.
     */
    public boolean isInSelectionMode() {
        return mSelectionMode != null;
    }

    /**
     * Called when a message is clicked.
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (view != mListFooterView) {
            MessageListItem itemView = (MessageListItem) view;
            onMessageOpen(itemView.mMailboxId, id);
        } else {
            doFooterClick();
        }
    }

    // This is tentative drag & drop UI
    private static class ShadowBuilder extends DragShadowBuilder {
        private static Drawable sBackground;
        /** Paint information for the move message text */
        private static TextPaint sMessagePaint;
        /** Paint information for the message count */
        private static TextPaint sCountPaint;
        /** The x location of any touch event; used to ensure the drag overlay is drawn correctly */
        private static int sTouchX;

        /** Width of the draggable view */
        private final int mDragWidth;
        /** Height of the draggable view */
        private final int mDragHeight;

        private final String mMessageText;
        private final PointF mMessagePoint;

        private final String mCountText;
        private final PointF mCountPoint;
        private int mOldOrientation = Configuration.ORIENTATION_UNDEFINED;

        /** Margin applied to the right of count text */
        private static float sCountMargin;
        /** Margin applied to left of the message text */
        private static float sMessageMargin;
        /** Vertical offset of the drag view */
        private static int sDragOffset;

        public ShadowBuilder(View view, int count) {
            super(view);
            Resources res = view.getResources();
            int newOrientation = res.getConfiguration().orientation;

            mDragHeight = view.getHeight();
            mDragWidth = view.getWidth();

            // TODO: Can we define a layout for the contents of the drag area?
            if (sBackground == null || mOldOrientation != newOrientation) {
                mOldOrientation = newOrientation;

                sBackground = res.getDrawable(R.drawable.bg_dragdrop);
                sBackground.setBounds(0, 0, mDragWidth, mDragHeight);

                sDragOffset = (int)res.getDimension(R.dimen.message_list_drag_offset);

                sMessagePaint = new TextPaint();
                float messageTextSize;
                messageTextSize = res.getDimension(R.dimen.message_list_drag_message_font_size);
                sMessagePaint.setTextSize(messageTextSize);
                sMessagePaint.setTypeface(Typeface.DEFAULT_BOLD);
                sMessagePaint.setAntiAlias(true);
                sMessageMargin = res.getDimension(R.dimen.message_list_drag_message_right_margin);

                sCountPaint = new TextPaint();
                float countTextSize;
                countTextSize = res.getDimension(R.dimen.message_list_drag_count_font_size);
                sCountPaint.setTextSize(countTextSize);
                sCountPaint.setTypeface(Typeface.DEFAULT_BOLD);
                sCountPaint.setAntiAlias(true);
                sCountMargin = res.getDimension(R.dimen.message_list_drag_count_left_margin);
            }

            // Calculate layout positions
            Rect b = new Rect();

            mMessageText = res.getQuantityString(R.plurals.move_messages, count, count);
            sMessagePaint.getTextBounds(mMessageText, 0, mMessageText.length(), b);
            mMessagePoint = new PointF(mDragWidth - b.right - sMessageMargin,
                    (mDragHeight - b.top)/ 2);

            mCountText = Integer.toString(count);
            sCountPaint.getTextBounds(mCountText, 0, mCountText.length(), b);
            mCountPoint = new PointF(sCountMargin,
                    (mDragHeight - b.top) / 2);
        }

        @Override
        public void onProvideShadowMetrics(Point shadowSize, Point shadowTouchPoint) {
            shadowSize.set(mDragWidth, mDragHeight);
            shadowTouchPoint.set(sTouchX, (mDragHeight / 2) + sDragOffset);
        }

        @Override
        public void onDrawShadow(Canvas canvas) {
            super.onDrawShadow(canvas);
            sBackground.draw(canvas);
            canvas.drawText(mMessageText, mMessagePoint.x, mMessagePoint.y, sMessagePaint);
            canvas.drawText(mCountText, mCountPoint.x, mCountPoint.y, sCountPaint);
        }
    }

    @Override
    public boolean onDrag(View view, DragEvent event) {
        switch(event.getAction()) {
            case DragEvent.ACTION_DRAG_ENDED:
                if (event.getResult()) {
                    onDeselectAll(); // Clear the selection
                }
                mCallback.onDragEnded();
                break;
        }
        return false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // Save the touch location to draw the drag overlay at the correct location
            ShadowBuilder.sTouchX = (int)event.getX();
        }
        // don't do anything, let the system process the event
        return false;
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (view != mListFooterView) {
            if (!mCallback.onDragStarted()) {
                return false; // D&D not allowed.
            }
            // We can't move from combined accounts view
            // We also need to check the actual mailbox to see if we can move items from it
            final long mailboxId = getMailboxId();
            if (mAccount == null || mMailbox == null) {
                return false;
            } else if (mailboxId > 0 && !Mailbox.canMoveFrom(mActivity, mailboxId)) {
                return false;
            }
            MessageListItem listItem = (MessageListItem)view;
            if (!mListAdapter.isSelected(listItem)) {
                toggleSelection(listItem);
            }
            // Start drag&drop.

            // Create ClipData with the Uri of the message we're long clicking
            ClipData data = ClipData.newUri(mActivity.getContentResolver(),
                    MessageListItem.MESSAGE_LIST_ITEMS_CLIP_LABEL, Message.CONTENT_URI.buildUpon()
                    .appendPath(Long.toString(listItem.mMessageId))
                    .appendQueryParameter(
                            EmailProvider.MESSAGE_URI_PARAMETER_MAILBOX_ID,
                            Long.toString(mailboxId))
                            .build());
            Set<Long> selectedMessageIds = mListAdapter.getSelectedSet();
            int size = selectedMessageIds.size();
            // Add additional Uri's for any other selected messages
            for (Long messageId: selectedMessageIds) {
                if (messageId.longValue() != listItem.mMessageId) {
                    data.addItem(new ClipData.Item(
                            ContentUris.withAppendedId(Message.CONTENT_URI, messageId)));
                }
            }
            // Start dragging now
            listItem.setOnDragListener(this);
            listItem.startDrag(data, new ShadowBuilder(listItem, size), null, 0);
            return true;
        }
        return false;
    }

    private void toggleSelection(MessageListItem itemView) {
        mListAdapter.toggleSelected(itemView);
    }

    /**
     * Called when a message on the list is selected
     *
     * @param messageMailboxId the actual mailbox ID of the message.  Note it's different than
     *        what is returned by {@link #getMailboxId()} for combined mailboxes.
     *        ({@link #getMailboxId()} may return special mailbox values such as
     *        {@link Mailbox#QUERY_ALL_INBOXES})
     * @param messageId ID of the message to open.
     */
    private void onMessageOpen(final long messageMailboxId, final long messageId) {
        new MessageOpenTask(messageMailboxId, messageId).cancelPreviousAndExecuteParallel();
    }

    /**
     * Task to look up the mailbox type for a message, and kicks the callback.
     */
    private class MessageOpenTask extends EmailAsyncTask<Void, Void, Integer> {
        private final long mMessageMailboxId;
        private final long mMessageId;

        public MessageOpenTask(long messageMailboxId, long messageId) {
            super(mTaskTracker);
            mMessageMailboxId = messageMailboxId;
            mMessageId = messageId;
        }

        @Override
        protected Integer doInBackground(Void... params) {
            // Restore the mailbox type.  Note we can't use mMailbox.mType here, because
            // we don't have mMailbox for combined mailbox.
            // ("All Starred" can contain any kind of messages.)
            switch (Mailbox.getMailboxType(mActivity, mMessageMailboxId)) {
                case Mailbox.TYPE_DRAFTS:
                    return Callback.TYPE_DRAFT;
                case Mailbox.TYPE_TRASH:
                    return Callback.TYPE_TRASH;
                default:
                    return Callback.TYPE_REGULAR;
            }
        }

        @Override
        protected void onSuccess(Integer type) {
            if (type == null) {
                return;
            }
            mCallback.onMessageOpen(mMessageId, mMessageMailboxId, getMailboxId(), type);
        }
    }

    private void showMoveMessagesDialog(Set<Long> selectedSet) {
        long[] messageIds = Utility.toPrimitiveLongArray(selectedSet);
        MoveMessageToDialog dialog = MoveMessageToDialog.newInstance(messageIds, this);
        dialog.show(getFragmentManager(), "dialog");
    }

    @Override
    public void onMoveToMailboxSelected(long newMailboxId, long[] messageIds) {
        mCallback.onAdvancingOpAccepted(Utility.toLongSet(messageIds));
        ActivityHelper.moveMessages(getActivity(), newMailboxId, messageIds);

        // Move is async, so we can't refresh now.  Instead, just clear the selection.
        onDeselectAll();
    }

    /**
     * Refresh the list.  NOOP for special mailboxes (e.g. combined inbox).
     *
     * Note: Manual refresh is enabled even for push accounts.
     */
    public void onRefresh(boolean userRequest) {
        if (mIsRefreshable) {
            mRefreshManager.refreshMessageList(getAccountId(), getMailboxId(), userRequest);
        }
    }

    private void onDeselectAll() {
        mListAdapter.clearSelection();
        if (isInSelectionMode()) {
            finishSelectionMode();
        }
    }

    /**
     * Load more messages.  NOOP for special mailboxes (e.g. combined inbox).
     */
    private void onLoadMoreMessages() {
        if (mIsRefreshable) {
            mRefreshManager.loadMoreMessages(getAccountId(), getMailboxId());
        }
    }

    public void onSendPendingMessages() {
        RefreshManager rm = RefreshManager.getInstance(mActivity);
        if (getMailboxId() == Mailbox.QUERY_ALL_OUTBOX) {
            rm.sendPendingMessagesForAllAccounts();
        } else if (mMailbox != null) { // Magic boxes don't have a specific account id.
            rm.sendPendingMessages(mMailbox.mAccountKey);
        }
    }

    private void onSetMessageRead(long messageId, boolean newRead) {
        mController.setMessageRead(messageId, newRead);
    }

    private void onSetMessageFavorite(long messageId, boolean newFavorite) {
        mController.setMessageFavorite(messageId, newFavorite);
    }

    /**
     * Toggles a set read/unread states.  Note, the default behavior is "mark unread", so the
     * sense of the helper methods is "true=unread".
     *
     * @param selectedSet The current list of selected items
     */
    private void toggleRead(Set<Long> selectedSet) {
        toggleMultiple(selectedSet, new MultiToggleHelper() {

            @Override
            public boolean getField(long messageId, Cursor c) {
                return c.getInt(MessagesAdapter.COLUMN_READ) == 0;
            }

            @Override
            public boolean setField(long messageId, Cursor c, boolean newValue) {
                boolean oldValue = getField(messageId, c);
                if (oldValue != newValue) {
                    onSetMessageRead(messageId, !newValue);
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * Toggles a set of favorites (stars)
     *
     * @param selectedSet The current list of selected items
     */
    private void toggleFavorite(Set<Long> selectedSet) {
        toggleMultiple(selectedSet, new MultiToggleHelper() {

            @Override
            public boolean getField(long messageId, Cursor c) {
                return c.getInt(MessagesAdapter.COLUMN_FAVORITE) != 0;
            }

            @Override
            public boolean setField(long messageId, Cursor c, boolean newValue) {
                boolean oldValue = getField(messageId, c);
                if (oldValue != newValue) {
                    onSetMessageFavorite(messageId, newValue);
                    return true;
                }
                return false;
            }
        });
    }

    private void deleteMessages(Set<Long> selectedSet) {
        final long[] messageIds = Utility.toPrimitiveLongArray(selectedSet);
        mController.deleteMessages(messageIds);
        Toast.makeText(mActivity, mActivity.getResources().getQuantityString(
                R.plurals.message_deleted_toast, messageIds.length), Toast.LENGTH_SHORT).show();
        selectedSet.clear();
        // Message deletion is async... Can't refresh the list immediately.
    }

    private interface MultiToggleHelper {
        /**
         * Return true if the field of interest is "set".  If one or more are false, then our
         * bulk action will be to "set".  If all are set, our bulk action will be to "clear".
         * @param messageId the message id of the current message
         * @param c the cursor, positioned to the item of interest
         * @return true if the field at this row is "set"
         */
        public boolean getField(long messageId, Cursor c);

        /**
         * Set or clear the field of interest.  Return true if a change was made.
         * @param messageId the message id of the current message
         * @param c the cursor, positioned to the item of interest
         * @param newValue the new value to be set at this row
         * @return true if a change was actually made
         */
        public boolean setField(long messageId, Cursor c, boolean newValue);
    }

    /**
     * Toggle multiple fields in a message, using the following logic:  If one or more fields
     * are "clear", then "set" them.  If all fields are "set", then "clear" them all.
     *
     * @param selectedSet the set of messages that are selected
     * @param helper functions to implement the specific getter & setter
     * @return the number of messages that were updated
     */
    private int toggleMultiple(Set<Long> selectedSet, MultiToggleHelper helper) {
        Cursor c = mListAdapter.getCursor();
        boolean anyWereFound = false;
        boolean allWereSet = true;

        c.moveToPosition(-1);
        while (c.moveToNext()) {
            long id = c.getInt(MessagesAdapter.COLUMN_ID);
            if (selectedSet.contains(Long.valueOf(id))) {
                anyWereFound = true;
                if (!helper.getField(id, c)) {
                    allWereSet = false;
                    break;
                }
            }
        }

        int numChanged = 0;

        if (anyWereFound) {
            boolean newValue = !allWereSet;
            c.moveToPosition(-1);
            while (c.moveToNext()) {
                long id = c.getInt(MessagesAdapter.COLUMN_ID);
                if (selectedSet.contains(Long.valueOf(id))) {
                    if (helper.setField(id, c, newValue)) {
                        ++numChanged;
                    }
                }
            }
        }

        return numChanged;
    }

    /**
     * Test selected messages for showing appropriate labels
     * @param selectedSet
     * @param column_id
     * @param defaultflag
     * @return true when the specified flagged message is selected
     */
    private boolean testMultiple(Set<Long> selectedSet, int column_id, boolean defaultflag) {
        Cursor c = mListAdapter.getCursor();
        if (c == null || c.isClosed()) {
            return false;
        }
        c.moveToPosition(-1);
        while (c.moveToNext()) {
            long id = c.getInt(MessagesAdapter.COLUMN_ID);
            if (selectedSet.contains(Long.valueOf(id))) {
                if (c.getInt(column_id) == (defaultflag ? 1 : 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return true if one or more non-starred messages are selected.
     */
    public boolean doesSelectionContainNonStarredMessage() {
        return testMultiple(mListAdapter.getSelectedSet(), MessagesAdapter.COLUMN_FAVORITE,
                false);
    }

    /**
     * @return true if one or more read messages are selected.
     */
    public boolean doesSelectionContainReadMessage() {
        return testMultiple(mListAdapter.getSelectedSet(), MessagesAdapter.COLUMN_READ, true);
    }

    /**
     * Implements a timed refresh of "stale" mailboxes.  This should only happen when
     * multiple conditions are true, including:
     *   Only refreshable mailboxes.
     *   Only when the mailbox is "stale" (currently set to 5 minutes since last refresh)
     * Note we do this even if it's a push account; even on Exchange only inbox can be pushed.
     */
    private void autoRefreshStaleMailbox() {
        if (!mIsRefreshable) {
            // Not refreshable (special box such as drafts, or magic boxes)
            return;
        }
        if (!mRefreshManager.isMailboxStale(getMailboxId())) {
            return;
        }
        onRefresh(false);
    }

    /** Implements {@link MessagesAdapter.Callback} */
    @Override
    public void onAdapterFavoriteChanged(MessageListItem itemView, boolean newFavorite) {
        onSetMessageFavorite(itemView.mMessageId, newFavorite);
    }

    /** Implements {@link MessagesAdapter.Callback} */
    @Override
    public void onAdapterSelectedChanged(
            MessageListItem itemView, boolean newSelected, int mSelectedCount) {
        updateSelectionMode();
    }

    private void updateSearchHeader(Cursor cursor) {
        MessageListContext listContext = getListContext();
        if (!listContext.isSearch() || cursor == null) {
            mSearchHeader.setVisibility(View.GONE);
            return;
        }

        SearchResultsCursor searchCursor = (SearchResultsCursor) cursor;
        mSearchHeader.setVisibility(View.VISIBLE);
        String header = String.format(
                mActivity.getString(R.string.search_header_text_fmt),
                listContext.getSearchParams().mFilter);
        mSearchHeaderText.setText(header);
        mSearchHeaderCount.setText(UiUtilities.getMessageCountForUi(
                mActivity, searchCursor.getResultsCount(), false /* replaceZeroWithBlank */));
    }

    private void determineFooterMode() {
        mListFooterMode = LIST_FOOTER_MODE_NONE;
        if ((mMailbox == null)
                || (mMailbox.mType == Mailbox.TYPE_OUTBOX)
                || (mMailbox.mType == Mailbox.TYPE_DRAFTS)) {
            return; // No footer
        }
        if (mMailbox.mType == Mailbox.TYPE_SEARCH) {
            // Determine how many results have been loaded.
            Cursor c = mListAdapter.getCursor();
            if (c == null || c.isClosed()) {
                // Unknown yet - don't do anything.
                return;
            }
            int total = ((SearchResultsCursor) c).getResultsCount();
            int loaded = c.getCount();

            if (loaded < total) {
                mListFooterMode = LIST_FOOTER_MODE_MORE;
            }
        } else if (!mIsEasAccount) {
            // IMAP, POP has "load more" for regular mailboxes.
            mListFooterMode = LIST_FOOTER_MODE_MORE;
        }
    }

    private void addFooterView() {
        // Only called from onLoadFinished -- always has views.
        ListView lv = getListView();
        if (mListFooterView != null) {
            lv.removeFooterView(mListFooterView);
        }
        determineFooterMode();
        if (mListFooterMode != LIST_FOOTER_MODE_NONE) {
            lv.addFooterView(mListFooterView);
            lv.setAdapter(mListAdapter);

            mListFooterProgress = mListFooterView.findViewById(R.id.progress);
            mListFooterText = (TextView) mListFooterView.findViewById(R.id.main_text);

            updateListFooter();
        }
    }

    /**
     * Set the list footer text based on mode and the current "network active" status
     */
    private void updateListFooter() {
        if (mListFooterMode != LIST_FOOTER_MODE_NONE) {
            int footerTextId = 0;
            switch (mListFooterMode) {
                case LIST_FOOTER_MODE_MORE:
                    boolean active = mRefreshManager.isMessageListRefreshing(getMailboxId());
                    footerTextId = active ? R.string.status_loading_messages
                            : R.string.message_list_load_more_messages_action;
                    mListFooterProgress.setVisibility(active ? View.VISIBLE : View.GONE);
                    break;
            }
            mListFooterText.setText(footerTextId);
        }
    }

    /**
     * Handle a click in the list footer, which changes meaning depending on what we're looking at.
     */
    private void doFooterClick() {
        switch (mListFooterMode) {
            case LIST_FOOTER_MODE_NONE: // should never happen
                break;
            case LIST_FOOTER_MODE_MORE:
                onLoadMoreMessages();
                break;
        }
    }

    private void showSendCommand(boolean show) {
        mShowSendCommand = show;
        mActivity.invalidateOptionsMenu();
    }

    private void showSendCommandIfNecessary() {
        final boolean isOutbox = (getMailboxId() == Mailbox.QUERY_ALL_OUTBOX)
                || ((mMailbox != null) && (mMailbox.mType == Mailbox.TYPE_OUTBOX));
        showSendCommand(isOutbox && (mListAdapter != null) && (mListAdapter.getCount() > 0));
    }

    private void showNoMessageText(boolean visible) {
        mNoMessagesPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        mListPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    /**
     * Adjusts message notification depending upon the state of the fragment and the currently
     * viewed mailbox. If the fragment is resumed, notifications for the current mailbox may
     * be suspended. Otherwise, notifications may be re-activated. Not all mailbox types are
     * supported for notifications. These include (but are not limited to) special mailboxes
     * such as {@link Mailbox#QUERY_ALL_DRAFTS}, {@link Mailbox#QUERY_ALL_FAVORITES}, etc...
     *
     * @param updateLastSeenKey If {@code true}, the last seen message key for the currently
     *                          viewed mailbox will be updated.
     */
    private void adjustMessageNotification(boolean updateLastSeenKey) {
        final long accountId = getAccountId();
        final long mailboxId = getMailboxId();
        if (mailboxId == Mailbox.QUERY_ALL_INBOXES || mailboxId > 0) {
            if (updateLastSeenKey) {
                Utility.updateLastSeenMessageKey(mActivity, accountId);
            }
            NotificationController notifier = NotificationController.getInstance(mActivity);
            notifier.suspendMessageNotification(mResumed, accountId);
        }
    }

    private void startLoading() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " startLoading");
        }
        // Clear the list. (ListFragment will show the "Loading" animation)
        showNoMessageText(false);
        showSendCommand(false);
        updateSearchHeader(null);

        // Start loading...
        final LoaderManager lm = getLoaderManager();
        lm.initLoader(LOADER_ID_MESSAGES_LOADER, null, new MessagesLoaderCallback());
    }

    /**
     * Loader callbacks for message list.
     */
    private class MessagesLoaderCallback implements LoaderManager.LoaderCallbacks<Cursor> {
        private boolean mIsFirstLoad;

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            final MessageListContext listContext = getListContext();
            if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Logging.LOG_TAG, MessageListFragment.this
                        + " onCreateLoader(messages) listContext=" + listContext);
            }
            mIsFirstLoad = true;
            return MessagesAdapter.createLoader(getActivity(), listContext);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor c) {
            if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Logging.LOG_TAG, MessageListFragment.this
                        + " onLoadFinished(messages) mailboxId=" + getMailboxId());
            }
            MessagesAdapter.MessagesCursor cursor = (MessagesAdapter.MessagesCursor) c;

            if (!cursor.mIsFound) {
                mCallback.onMailboxNotFound();
                return;
            }

            // Get the "extras" part.
            mAccount = cursor.mAccount;
            mMailbox = cursor.mMailbox;
            mIsEasAccount = cursor.mIsEasAccount;
            mIsRefreshable = cursor.mIsRefreshable;
            mCountTotalAccounts = cursor.mCountTotalAccounts;

            // Suspend message notifications as long as we're resumed
            adjustMessageNotification(false);

            // Save list view state (primarily scroll position)
            final ListView lv = getListView();
            final Parcelable listState;
            if (mSavedListState != null) {
                listState = mSavedListState;
                mSavedListState = null;
            } else {
                listState = lv.onSaveInstanceState();
            }

            // If this is a search mailbox, set the query; otherwise, clear it
            if (mMailbox != null && mMailbox.mType == Mailbox.TYPE_SEARCH) {
                mListAdapter.setQuery(getListContext().getSearchParams().mFilter);
                mSearchedMailbox = ((SearchResultsCursor) c).getSearchedMailbox();
            } else {
                mListAdapter.setQuery(null);
                mSearchedMailbox = null;
            }

            // Update the list
            mListAdapter.swapCursor(cursor);
            // Show chips if combined view.
            mListAdapter.setShowColorChips(isCombinedMailbox() && mCountTotalAccounts > 1);

            // Various post processing...
            updateSearchHeader(cursor);
            autoRefreshStaleMailbox();
            addFooterView();
            updateSelectionMode();
            showSendCommandIfNecessary();
            showNoMessageText((cursor.getCount() == 0)
                    && (getListContext().isSearch() || (mListFooterMode == LIST_FOOTER_MODE_NONE)));

            // We want to make visible the selection only for the first load.
            // Re-load caused by content changed events shouldn't scroll the list.
            highlightSelectedMessage(mIsFirstLoad);

            // Restore the state -- this step has to be the last, because Some of the
            // "post processing" seems to reset the scroll position.
            lv.onRestoreInstanceState(listState);

            // Clear this for next reload triggered by content changed events.
            mIsFirstLoad = false;

            mCallback.onListLoaded();
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Logging.LOG_TAG, MessageListFragment.this
                        + " onLoaderReset(messages)");
            }
            mListAdapter.swapCursor(null);
            mAccount = null;
            mMailbox = null;
            mSearchedMailbox = null;
            mCountTotalAccounts = 0;
        }
    }

    /**
     * Show/hide the "selection" action mode, according to the number of selected messages and
     * the visibility of the fragment.
     * Also update the content (title and menus) if necessary.
     */
    public void updateSelectionMode() {
        final int numSelected = getSelectedCount();
        if ((numSelected == 0) || mDisableCab || !isViewCreated()) {
            finishSelectionMode();
            return;
        }
        if (isInSelectionMode()) {
            updateSelectionModeView();
        } else {
            mLastSelectionModeCallback = new SelectionModeCallback();
            getActivity().startActionMode(mLastSelectionModeCallback);
        }
    }


    /**
     * Finish the "selection" action mode.
     *
     * Note this method finishes the contextual mode, but does *not* clear the selection.
     * If you want to do so use {@link #onDeselectAll()} instead.
     */
    private void finishSelectionMode() {
        if (isInSelectionMode()) {
            mLastSelectionModeCallback.mClosedByUser = false;
            mSelectionMode.finish();
        }
    }

    /** Update the "selection" action mode bar */
    private void updateSelectionModeView() {
        mSelectionMode.invalidate();
    }

    private class SelectionModeCallback implements ActionMode.Callback {
        private MenuItem mMarkRead;
        private MenuItem mMarkUnread;
        private MenuItem mAddStar;
        private MenuItem mRemoveStar;

        /* package */ boolean mClosedByUser = true;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mSelectionMode = mode;

            MenuInflater inflater = getActivity().getMenuInflater();
            inflater.inflate(R.menu.message_list_fragment_cab_options, menu);
            mMarkRead = menu.findItem(R.id.mark_read);
            mMarkUnread = menu.findItem(R.id.mark_unread);
            mAddStar = menu.findItem(R.id.add_star);
            mRemoveStar = menu.findItem(R.id.remove_star);

            mCallback.onEnterSelectionMode(true);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            int num = getSelectedCount();
            // Set title -- "# selected"
            mSelectionMode.setTitle(getActivity().getResources().getQuantityString(
                    R.plurals.message_view_selected_message_count, num, num));

            // Show appropriate menu items.
            boolean nonStarExists = doesSelectionContainNonStarredMessage();
            boolean readExists = doesSelectionContainReadMessage();
            mMarkRead.setVisible(!readExists);
            mMarkUnread.setVisible(readExists);
            mAddStar.setVisible(nonStarExists);
            mRemoveStar.setVisible(!nonStarExists);
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            Set<Long> selectedConversations = mListAdapter.getSelectedSet();
            switch (item.getItemId()) {
                case R.id.mark_read:
                    // Note - marking as read does not trigger auto-advance.
                    toggleRead(selectedConversations);
                    break;
                case R.id.mark_unread:
                    mCallback.onAdvancingOpAccepted(selectedConversations);
                    toggleRead(selectedConversations);
                    break;
                case R.id.add_star:
                case R.id.remove_star:
                    // TODO: removing a star can be a destructive command and cause auto-advance
                    // if the current mailbox shown is favorites.
                    toggleFavorite(selectedConversations);
                    break;
                case R.id.delete:
                    mCallback.onAdvancingOpAccepted(selectedConversations);
                    deleteMessages(selectedConversations);
                    break;
                case R.id.move:
                    showMoveMessagesDialog(selectedConversations);
                    break;
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mCallback.onEnterSelectionMode(false);

            // Clear this before onDeselectAll() to prevent onDeselectAll() from trying to close the
            // contextual mode again.
            mSelectionMode = null;
            if (mClosedByUser) {
                // Clear selection, only when the contextual mode is explicitly closed by the user.
                //
                // We close the contextual mode when the fragment becomes temporary invisible
                // (i.e. mIsVisible == false) too, in which case we want to keep the selection.
                onDeselectAll();
            }
        }
    }

    private class RefreshListener implements RefreshManager.Listener {
        @Override
        public void onMessagingError(long accountId, long mailboxId, String message) {
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            updateListFooter();
        }
    }

    /**
     * Highlight the selected message.
     */
    private void highlightSelectedMessage(boolean ensureSelectionVisible) {
        if (!isViewCreated()) {
            return;
        }

        final ListView lv = getListView();
        if (mSelectedMessageId == -1) {
            // No message selected
            lv.clearChoices();
            return;
        }

        final int count = lv.getCount();
        for (int i = 0; i < count; i++) {
            if (lv.getItemIdAtPosition(i) != mSelectedMessageId) {
                continue;
            }
            lv.setItemChecked(i, true);
            if (ensureSelectionVisible) {
                Utility.listViewSmoothScrollToPosition(getActivity(), lv, i);
            }
            break;
        }
    }
}
