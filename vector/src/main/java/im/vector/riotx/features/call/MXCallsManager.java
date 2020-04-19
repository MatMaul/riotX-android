/*
 * Copyright 2015 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.riotx.features.call;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import im.vector.matrix.android.api.MatrixCallback;
import im.vector.matrix.android.api.session.Session;
import im.vector.matrix.android.api.session.events.model.Event;
import im.vector.matrix.android.api.session.events.model.EventKt;
import im.vector.matrix.android.api.session.events.model.EventType;
import im.vector.matrix.android.api.session.room.Room;
import im.vector.matrix.android.api.session.room.members.RoomMemberQueryParams;
import im.vector.matrix.android.api.session.room.model.RoomMemberSummary;
import im.vector.matrix.android.api.session.room.model.call.CallInviteContent;
import im.vector.matrix.android.api.session.voip.TurnServer;
import im.vector.matrix.android.internal.crypto.model.MXUsersDevicesMap;
import im.vector.matrix.android.internal.crypto.model.rest.DeviceInfo;

public class MXCallsManager {
    private static final String LOG_TAG = MXCallsManager.class.getSimpleName();

    private Session mSession;
    private Context mContext;

    private TurnServer mTurnServer = null;
    private Timer mTurnServerTimer = null;
    private boolean mSuspendTurnServerRefresh = false;

    // active calls
    private final Map<String, IMXCall> mCallsByCallId = new HashMap<>();

    // listeners
    private final Set<IMXCallsManagerListener> mListeners = new HashSet<>();

    // incoming calls
    private final Set<String> mxPendingIncomingCallId = new HashSet<>();

    // UI handler
    private final Handler mUIThreadHandler;

    public static String defaultStunServerUri;

    /*
     * To create an outgoing call
     * 1- CallsManager.createCallInRoom()
     * 2- on success, IMXCall.createCallView
     * 3- IMXCallListener.onCallViewCreated(callview) -> insert the callview
     * 4- IMXCallListener.onCallReady() -> IMXCall.placeCall()
     * 5- the call states should follow theses steps
     *    CALL_STATE_WAIT_LOCAL_MEDIA
     *    CALL_STATE_WAIT_CREATE_OFFER
     *    CALL_STATE_INVITE_SENT
     *    CALL_STATE_RINGING
     * 6- the callee accepts the call
     *    CALL_STATE_CONNECTING
     *    CALL_STATE_CONNECTED
     *
     * To manage an incoming call
     * 1- IMXCall.createCallView
     * 2- IMXCallListener.onCallViewCreated(callview) -> insert the callview
     * 3- IMXCallListener.onCallReady(), IMXCall.launchIncomingCall()
     * 4- the call states should follow theses steps
     *    CALL_STATE_WAIT_LOCAL_MEDIA
     *    CALL_STATE_RINGING
     * 5- The user accepts the call, IMXCall.answer()
     * 6- the states should be
     *    CALL_STATE_CREATE_ANSWER
     *    CALL_STATE_CONNECTING
     *    CALL_STATE_CONNECTED
     */

    /**
     * Constructor
     *
     * @param session the session
     * @param context the context
     */
    public MXCallsManager(Session session, Context context) {
        mSession = session;
        mContext = context;

        mUIThreadHandler = new Handler(Looper.getMainLooper());

        // TODO

//        for (RoomSummary rs: mSession.getRoomSummaries(null)) {
//            Room room = mSession.getRoom(rs.getRoomId());
//            Timeline timeline = room.createTimeline(null, null);
//            timeline.addListener(new Timeline.Listener() {
//                @Override
//                public void onTimelineUpdated(@NotNull List<TimelineEvent> snapshot) {
//
//                }
//
//                @Override
//                public void onTimelineFailure(@NotNull Throwable throwable) {
//
//                }
//
//                @Override
//                public void onNewTimelineEvents(@NotNull List<String> eventIds) {
//
//                }
//            });
//        }
//
//            @Override
//            public void onLiveEvent(Event event, RoomState roomState) {
//                if (TextUtils.equals(event.getType(), EventType.STATE_ROOM_MEMBER)) {
//                    // Listen to the membership join/leave events to detect the conference user activity.
//                    // This mechanism detects the presence of an established conf call
//                    if (TextUtils.equals(event.getSenderId(), MXCallsManager.getConferenceUserId(event.getRoomId()))) {
//                        EventContent eventContent = JsonUtils.toEventContent(event.getContentAsJsonObject());
//
//                        if (TextUtils.equals(eventContent.membership, RoomMember.MEMBERSHIP_LEAVE)) {
//                            dispatchOnVoipConferenceFinished(event.roomId);
//                        }
//                        if (TextUtils.equals(eventContent.membership, RoomMember.MEMBERSHIP_JOIN)) {
//                            dispatchOnVoipConferenceStarted(event.roomId);
//                        }
//                    }
//                }
//            }


        if (isSupported()) {
            refreshTurnServer();
        }
    }

    /**
     * @return true if the call feature is supported
     * @apiNote Performs an implicit initialization of the PeerConnectionFactory
     */
    public boolean isSupported() {
        return MXWebRtcCall.isSupported(mContext);
    }

    /**
     * create a new call
     *
     * @param callId the call Id (null to use a default value)
     * @return the IMXCall
     */
    private IMXCall createCall(String callId) {
        Log.d(LOG_TAG, "createCall " + callId);

        IMXCall call = null;

        try {
            call = new MXWebRtcCall(mSession, mContext, getTurnServer(), defaultStunServerUri);
            // a valid callid is provided
            if (null != callId) {
                call.setCallId(callId);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "createCall " + e.getMessage(), e);
        }

        return call;
    }

    /**
     * Search a call from its dedicated room id.
     *
     * @param roomId the room id
     * @return the IMXCall if it exists
     */
    public IMXCall getCallWithRoomId(String roomId) {
        List<IMXCall> calls;

        synchronized (this) {
            calls = new ArrayList<>(mCallsByCallId.values());
        }

        for (IMXCall call : calls) {
            if (TextUtils.equals(roomId, call.getRoom().getRoomId())) {
                if (TextUtils.equals(call.getCallState(), IMXCall.CALL_STATE_ENDED)) {
                    Log.d(LOG_TAG, "## getCallWithRoomId() : the call " + call.getCallId() + " has been stopped");
                    synchronized (this) {
                        mCallsByCallId.remove(call.getCallId());
                    }
                } else {
                    return call;
                }
            }
        }

        return null;
    }

    /**
     * Returns the IMXCall from its callId.
     *
     * @param callId the call Id
     * @return the IMXCall if it exists
     */
    public IMXCall getCallWithCallId(String callId) {
        return getCallWithCallId(callId, false);
    }

    /**
     * Returns the IMXCall from its callId.
     *
     * @param callId the call Id
     * @param create create the IMXCall if it does not exist
     * @return the IMXCall if it exists
     */
    private IMXCall getCallWithCallId(String callId, boolean create) {
        IMXCall call = null;

        // check if the call exists
        if (null != callId) {
            synchronized (this) {
                call = mCallsByCallId.get(callId);
            }
        }

        // test if the call has been stopped
        if ((null != call) && TextUtils.equals(call.getCallState(), IMXCall.CALL_STATE_ENDED)) {
            Log.d(LOG_TAG, "## getCallWithCallId() : the call " + callId + " has been stopped");
            synchronized (this) {
                mCallsByCallId.remove(call.getCallId());
            }

            call = null;
        }

        // the call does not exist but request to create it
        if ((null == call) && create) {
            call = createCall(callId);
            synchronized (this) {
                mCallsByCallId.put(call.getCallId(), call);
            }
        }

        Log.d(LOG_TAG, "getCallWithCallId " + callId + " " + call);

        return call;
    }

    /**
     * Tell if a call is in progress.
     *
     * @param call the call
     * @return true if the call is in progress
     */
    public static boolean isCallInProgress(IMXCall call) {
        boolean res = false;

        if (null != call) {
            String callState = call.getCallState();
            res = TextUtils.equals(callState, IMXCall.CALL_STATE_CREATED)
                    || TextUtils.equals(callState, IMXCall.CALL_STATE_CREATING_CALL_VIEW)
                    || TextUtils.equals(callState, IMXCall.CALL_STATE_READY)
                    || TextUtils.equals(callState, IMXCall.CALL_STATE_WAIT_LOCAL_MEDIA)
                    || TextUtils.equals(callState, IMXCall.CALL_STATE_WAIT_CREATE_OFFER)
                    || TextUtils.equals(callState, IMXCall.CALL_STATE_INVITE_SENT)
                    || TextUtils.equals(callState, IMXCall.CALL_STATE_RINGING)
                    || TextUtils.equals(callState, IMXCall.CALL_STATE_CREATE_ANSWER)
                    || TextUtils.equals(callState, IMXCall.CALL_STATE_CONNECTING)
                    || TextUtils.equals(callState, IMXCall.CALL_STATE_CONNECTED);
        }

        return res;
    }

    /**
     * @return true if there are some active calls.
     */
    public boolean hasActiveCalls() {
        synchronized (this) {
            List<String> callIdsToRemove = new ArrayList<>();

            Set<String> callIds = mCallsByCallId.keySet();

            for (String callId : callIds) {
                IMXCall call = mCallsByCallId.get(callId);

                if (null != call && TextUtils.equals(call.getCallState(), IMXCall.CALL_STATE_ENDED)) {
                    Log.d(LOG_TAG, "# hasActiveCalls() : the call " + callId + " is not anymore valid");
                    callIdsToRemove.add(callId);
                } else {
                    Log.d(LOG_TAG, "# hasActiveCalls() : the call " + callId + " is active");
                    return true;
                }
            }

            for (String callIdToRemove : callIdsToRemove) {
                mCallsByCallId.remove(callIdToRemove);
            }
        }

        Log.d(LOG_TAG, "# hasActiveCalls() : no active call");
        return false;
    }

    /**
     * Manage the call events.
     *
     * @param event the call event
     */
    public void handleCallEvent(final Event event) {
        if (EventKt.isCallEvent(event) && isSupported()) {
            Log.d(LOG_TAG, "handleCallEvent " + event.getType());

            // always run the call event in the UI thread
            // TODO: This was introduced because of MXChromeCall, check if it is required for MXWebRtcCall as well
            mUIThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    boolean isMyEvent = TextUtils.equals(event.getSenderId(), mSession.getMyUserId());
                    Room room = mSession.getRoom(event.getRoomId());


                    String callId = null;
                    Map<String, Object> eventContent = null;

                    try {
                        eventContent = event.getClearContent();
                        callId = (String)eventContent.get("call_id");
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "handleCallEvent : fail to retrieve call_id " + e.getMessage(), e);
                    }
                    // sanity check
                    if ((null != callId) && (null != room)) {
                        // receive an invitation
                        if (EventType.CALL_INVITE.equals(event.getType())) {
                            long lifeTime = event.getAgeLocalTs();

                            if (Long.MAX_VALUE == lifeTime) {
                                lifeTime = System.currentTimeMillis() - event.getOriginServerTs();
                            }

                            // ignore older call messages
                            if (lifeTime < MXCall.CALL_TIMEOUT_MS) {
                                // create the call only it is triggered from someone else
                                IMXCall call = getCallWithCallId(callId, !isMyEvent);

                                // sanity check
                                if (null != call) {
                                    // init the information
                                    if (null == call.getRoom()) {
                                        call.setRooms(room, room);
                                    }

                                    if (!isMyEvent) {
                                        call.prepareIncomingCall(EventKt.toModel(eventContent, CallInviteContent.class, true), callId, null);
                                        mxPendingIncomingCallId.add(callId);
                                    } else {
                                        call.handleCallEvent(event);
                                    }
                                }
                            } else {
                                Log.d(LOG_TAG, "## handleCallEvent() : " + EventType.CALL_INVITE + " is ignored because it is too old");
                            }
                        } else if (EventType.CALL_CANDIDATES.equals(event.getType())) {
                            if (!isMyEvent) {
                                IMXCall call = getCallWithCallId(callId);

                                if (null != call) {
                                    if (null == call.getRoom()) {
                                        call.setRooms(room, room);
                                    }
                                    call.handleCallEvent(event);
                                }
                            }
                        } else if (EventType.CALL_ANSWER.equals(event.getType())) {
                            IMXCall call = getCallWithCallId(callId);

                            if (null != call) {
                                // assume it is a catch up call.
                                // the creation / candidates /
                                // the call has been answered on another device
                                if (IMXCall.CALL_STATE_CREATED.equals(call.getCallState())) {
                                    call.onAnsweredElsewhere();
                                    synchronized (this) {
                                        mCallsByCallId.remove(callId);
                                    }
                                } else {
                                    if (null == call.getRoom()) {
                                        call.setRooms(room, room);
                                    }
                                    call.handleCallEvent(event);
                                }
                            }
                        } else if (EventType.CALL_HANGUP.equals(event.getType())) {
                            final IMXCall call = getCallWithCallId(callId);
                            if (null != call) {
                                // trigger call events only if the call is active
                                final boolean isActiveCall = !IMXCall.CALL_STATE_CREATED.equals(call.getCallState());

                                if (null == call.getRoom()) {
                                    call.setRooms(room, room);
                                }

                                if (isActiveCall) {
                                    call.handleCallEvent(event);
                                }

                                synchronized (this) {
                                    mCallsByCallId.remove(callId);
                                }

                                // warn that a call has been hung up
                                mUIThreadHandler.post(() -> {
                                    // must warn anyway any listener that the call has been killed
                                    // for example, when the device is in locked screen
                                    // the callview is not created but the device is ringing
                                    // if the other participant ends the call, the ring should stop
                                    dispatchOnCallHangUp(call);
                                });
                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * check if there is a pending incoming call
     */
    public void checkPendingIncomingCalls() {
        //Log.d(LOG_TAG, "checkPendingIncomingCalls");

        mUIThreadHandler.post(() -> {
            if (mxPendingIncomingCallId.size() > 0) {
                for (String callId : mxPendingIncomingCallId) {
                    final IMXCall call = getCallWithCallId(callId);

                    if (null != call) {
                        final Room room = call.getRoom();

                        // for encrypted rooms with 2 members
                        // check if there are some unknown devices before warning
                        // of the incoming call.
                        // If there are some unknown devices, the answer event would not be encrypted.
                        if ((null != room)
                                && room.isEncrypted()
                                && mSession.cryptoService().getWarnOnUnknownDevices()
                                && room.getNumberOfJoinedMembers() == 2) {

                            // test if the encrypted events are sent only to the verified devices (any room)
                            boolean sendToVerifiedDevicesOnly = mSession.cryptoService().getGlobalBlacklistUnverifiedDevices();

                            if (sendToVerifiedDevicesOnly) {
                                dispatchOnIncomingCall(call, null);
                            } else {
                                //  test if the encrypted events are sent only to the verified devices (only this room)
                                sendToVerifiedDevicesOnly = mSession.cryptoService().isRoomBlacklistUnverifiedDevices(room.getRoomId());
                                if (sendToVerifiedDevicesOnly) {
                                    dispatchOnIncomingCall(call, null);
                                } else {
                                    List<RoomMemberSummary> members = room.getRoomMembers(new RoomMemberQueryParams(null, null, null, false));

                                    String userId1 = members.get(0).getUserId();
                                    String userId2 = members.get(1).getUserId();

                                    Log.d(LOG_TAG, "## checkPendingIncomingCalls() : check the unknown devices");

                                    // TODO
                                    dispatchOnIncomingCall(call, null);
//                                    mSession.cryptoService().checkUnknownDevices(Arrays.asList(userId1, userId2), new ApiCallback<Void>() {
//                                        @Override
//                                        public void onSuccess(Void anything) {
//                                            Log.d(LOG_TAG, "## checkPendingIncomingCalls() : no unknown device");
//                                            dispatchOnIncomingCall(call, null);
//                                        }
//
//                                        @Override
//                                        public void onNetworkError(Exception e) {
//                                            Log.e(LOG_TAG,
//                                                    "## checkPendingIncomingCalls() : checkUnknownDevices failed "
//                                                            + e.getMessage(), e);
//                                            dispatchOnIncomingCall(call, null);
//                                        }
//
//                                        @Override
//                                        public void onMatrixError(MatrixError e) {
//                                            MXUsersDevicesMap<MXDeviceInfo> unknownDevices = null;
//
//                                            if (e instanceof MXCryptoError) {
//                                                MXCryptoError cryptoError = (MXCryptoError) e;
//
//                                                if (MXCryptoError.UNKNOWN_DEVICES_CODE.equals(cryptoError.errcode)) {
//                                                    unknownDevices =
//                                                            (MXUsersDevicesMap<MXDeviceInfo>) cryptoError.mExceptionData;
//                                                }
//                                            }
//
//                                            if (null != unknownDevices) {
//                                                Log.d(LOG_TAG, "## checkPendingIncomingCalls() :" +
//                                                        " checkUnknownDevices found some unknown devices");
//                                            } else {
//                                                Log.e(LOG_TAG, "## checkPendingIncomingCalls() :" +
//                                                        " checkUnknownDevices failed " + e.getMessage());
//                                            }
//
//                                            dispatchOnIncomingCall(call, unknownDevices);
//                                        }
//
//                                        @Override
//                                        public void onUnexpectedError(Exception e) {
//                                            Log.e(LOG_TAG, "## checkPendingIncomingCalls() :" +
//                                                    " checkUnknownDevices failed " + e.getMessage(), e);
//                                            dispatchOnIncomingCall(call, null);
//                                        }
//                                    });
                                }
                            }
                        } else {
                            dispatchOnIncomingCall(call, null);
                        }
                    }
                }
            }
            mxPendingIncomingCallId.clear();
        });
    }

    /**
     * Create an IMXCall in the room defines by its room Id.
     * -> for a 1:1 call, it is a standard call.
     * -> for a conference call,
     * ----> the conference user is invited to the room (if it was not yet invited)
     * ----> the call signaling room is created (or retrieved) with the conference
     * ----> and the call is started
     *
     * @param roomId   the room roomId
     * @param isVideo  true to start a video call
     * @param callback the async callback
     */
    public void createCallInRoom(final String roomId, final boolean isVideo, final MatrixCallback<IMXCall> callback) {
        Log.d(LOG_TAG, "createCallInRoom in " + roomId);

        final Room room = mSession.getRoom(roomId);

        // sanity check
        if (null != room) {
            if (isSupported()) {
                int joinedMembers = room.getNumberOfJoinedMembers();

                Log.d(LOG_TAG, "createCallInRoom : the room has " + joinedMembers + " joined members");

                if (joinedMembers == 2) {
                        // when a room is encrypted, test first there is no unknown device
                        // else the call will fail.
                        // So it seems safer to reject the call creation it it will fail.
                        // TODO
//                        if (room.isEncrypted() && mSession.cryptoService().getWarnOnUnknownDevices()) {
//                            List<RoomMemberSummary> members = room.getRoomMembers(new RoomMemberQueryParams(null, null, null, false));
//                                    if (members.size() != 2) {
//                                        // Safety check
//                                        callback.onFailure(new Exception("Wrong number of members"));
//                                        return;
//                                    }
//
//                                    String userId1 = members.get(0).getUserId();
//                                    String userId2 = members.get(1).getUserId();
//
//                                    // force the refresh to ensure that the devices list is up-to-date
//                                    mSession.cryptoService().checkUnknownDevices(Arrays.asList(userId1, userId2), new SimpleApiCallback<Void>(callback) {
//                                        @Override
//                                        public void onSuccess(Void anything) {
//                                            final IMXCall call = getCallWithCallId(null, true);
//                                            call.setRooms(room, room);
//                                            call.setIsVideo(isVideo);
//                                            dispatchOnOutgoingCall(call);
//
//                                            if (null != callback) {
//                                                mUIThreadHandler.post(() -> callback.onSuccess(call));
//                                            }
//                                        }
//                                    });
//                                }
//                            });
//                        } else {

                    final IMXCall call = getCallWithCallId(null, true);
                    call.setIsVideo(isVideo);
                    dispatchOnOutgoingCall(call);
                    call.setRooms(room, room);

                    if (null != callback) {
                        mUIThreadHandler.post(() -> callback.onSuccess(call));
                    }
                } else {
                    if (null != callback) {
                        callback.onFailure(new Exception("WebRTC VoIP: not a 1:1 room"));
                    }
                }
            } else {
                if (null != callback) {
                    callback.onFailure(new Exception("VoIP is not supported"));
                }
            }
        } else {
            if (null != callback) {
                callback.onFailure(new Exception("Room not found"));
            }
        }
    }

    //==============================================================================================================
    // Turn servers management
    //==============================================================================================================

    /**
     * Suspend the turn server  refresh
     */
    public void pauseTurnServerRefresh() {
        mSuspendTurnServerRefresh = true;
    }

    /**
     * Refresh the turn servers until it succeeds.
     */
    public void unpauseTurnServerRefresh() {
        Log.d(LOG_TAG, "unpauseTurnServerRefresh");

        mSuspendTurnServerRefresh = false;
        if (null != mTurnServerTimer) {
            mTurnServerTimer.cancel();
            mTurnServerTimer = null;
        }
        refreshTurnServer();
    }

    /**
     * Stop the turn servers refresh.
     */
    public void stopTurnServerRefresh() {
        Log.d(LOG_TAG, "stopTurnServerRefresh");

        mSuspendTurnServerRefresh = true;
        if (null != mTurnServerTimer) {
            mTurnServerTimer.cancel();
            mTurnServerTimer = null;
        }
    }

    /**
     * @return the turn server
     */
    private TurnServer getTurnServer() {
        TurnServer res;

        synchronized (LOG_TAG) {
            res = mTurnServer;
        }

        // privacy logs
        //Log.d(LOG_TAG, "getTurnServer " + res);
        Log.d(LOG_TAG, "getTurnServer ");

        return res;
    }

    private void restartAfter(int msDelay) {
        // reported by GA
        // "ttl" seems invalid
        if (msDelay <= 0) {
            Log.e(LOG_TAG, "## refreshTurnServer() : invalid delay " + msDelay);
        } else {
            if (null != mTurnServerTimer) {
                mTurnServerTimer.cancel();
            }

            try {
                mTurnServerTimer = new Timer();
                mTurnServerTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (mTurnServerTimer != null) {
                            mTurnServerTimer.cancel();
                            mTurnServerTimer = null;
                        }

                        refreshTurnServer();
                    }
                }, msDelay);
            } catch (Throwable e) {
                Log.e(LOG_TAG, "## refreshTurnServer() failed to start the timer", e);

                if (null != mTurnServerTimer) {
                    mTurnServerTimer.cancel();
                    mTurnServerTimer = null;
                }
                refreshTurnServer();
            }
        }
    }

    /**
     * Refresh the turn servers.
     */
    private void refreshTurnServer() {
        if (mSuspendTurnServerRefresh) {
            return;
        }

        Log.d(LOG_TAG, "## refreshTurnServer () starts");

        TurnServer t = mSession.getTurnServer();



        // TODO UIThread needed ?
        mUIThreadHandler.post(() -> {
            TurnServer turnServer = mSession.getTurnServer();

            if (null != turnServer) {
                if (turnServer.getUris() != null) {
                    synchronized (LOG_TAG) {
                        mTurnServer = turnServer;
                    }
                }

                if (turnServer.getTtl() != null) {
                    int ttl = 60000;

                    try {
                        // restart a 90 % before ttl expires
                        ttl = turnServer.getTtl() * 9 / 10;
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Fail to retrieve ttl " + e.getMessage(), e);
                    }

                    Log.d(LOG_TAG, "## refreshTurnServer () : onSuccess : retry after " + ttl + " seconds");
                    restartAfter(ttl * 1000);
                }
            }
        });
    }

    //==============================================================================================================
    // listeners management
    //==============================================================================================================

    /**
     * Add a listener
     *
     * @param listener the listener to add
     */
    public void addListener(IMXCallsManagerListener listener) {
        if (null != listener) {
            synchronized (this) {
                mListeners.add(listener);
            }
        }
    }

    /**
     * Remove a listener
     *
     * @param listener the listener to remove
     */
    public void removeListener(IMXCallsManagerListener listener) {
        if (null != listener) {
            synchronized (this) {
                mListeners.remove(listener);
            }
        }
    }

    /**
     * @return a copy of the listeners
     */
    private Collection<IMXCallsManagerListener> getListeners() {
        Collection<IMXCallsManagerListener> listeners;

        synchronized (this) {
            listeners = new HashSet<>(mListeners);
        }

        return listeners;
    }

    /**
     * dispatch the onIncomingCall event to the listeners
     *
     * @param call           the call
     * @param unknownDevices the unknown e2e devices list.
     */
    private void dispatchOnIncomingCall(IMXCall call, final MXUsersDevicesMap<DeviceInfo> unknownDevices) {
        Log.d(LOG_TAG, "dispatchOnIncomingCall " + call.getCallId());

        Collection<IMXCallsManagerListener> listeners = getListeners();

        for (IMXCallsManagerListener l : listeners) {
            try {
                l.onIncomingCall(call, unknownDevices);
            } catch (Exception e) {
                Log.e(LOG_TAG, "dispatchOnIncomingCall " + e.getMessage(), e);
            }
        }
    }

    /**
     * dispatch the call creation to the listeners
     *
     * @param call the call
     */
    private void dispatchOnOutgoingCall(IMXCall call) {
        Log.d(LOG_TAG, "dispatchOnOutgoingCall " + call.getCallId());

        Collection<IMXCallsManagerListener> listeners = getListeners();

        for (IMXCallsManagerListener l : listeners) {
            try {
                l.onOutgoingCall(call);
            } catch (Exception e) {
                Log.e(LOG_TAG, "dispatchOnOutgoingCall " + e.getMessage(), e);
            }
        }
    }

    /**
     * dispatch the onCallHangUp event to the listeners
     *
     * @param call the call
     */
    private void dispatchOnCallHangUp(IMXCall call) {
        Log.d(LOG_TAG, "dispatchOnCallHangUp");

        Collection<IMXCallsManagerListener> listeners = getListeners();

        for (IMXCallsManagerListener l : listeners) {
            try {
                l.onCallHangUp(call);
            } catch (Exception e) {
                Log.e(LOG_TAG, "dispatchOnCallHangUp " + e.getMessage(), e);
            }
        }
    }
}
