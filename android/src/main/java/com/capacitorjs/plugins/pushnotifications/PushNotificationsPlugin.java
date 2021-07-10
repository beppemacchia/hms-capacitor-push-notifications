package com.capacitorjs.plugins.pushnotifications;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.capacitorjs.plugins.hmslocalnotifications.LocalNotification;
import com.capacitorjs.plugins.hmslocalnotifications.LocalNotificationManager;
import com.capacitorjs.plugins.hmslocalnotifications.NotificationStorage;
import com.getcapacitor.Bridge;
import com.getcapacitor.CapConfig;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginHandle;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.huawei.agconnect.AGConnectInstance;
import com.huawei.hms.aaid.HmsInstanceId;
import com.huawei.hms.common.ApiException;
import com.huawei.hms.push.HmsMessaging;
import com.huawei.hms.push.RemoteMessage;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(name = "PushNotifications", permissions = @Permission(strings = {}, alias = "receive"))
public class PushNotificationsPlugin extends Plugin {

    private static final String CLIENT_APP_ID = "client/app_id";
    private static final String HCM = "HCM";

    public static Bridge staticBridge = null;
    public static RemoteMessage lastMessage = null;
    public NotificationManager notificationManager;
    public MessagingService huaweiMessagingService;
    private NotificationChannelManager notificationChannelManager;

    private static final String EVENT_TOKEN_CHANGE = "registration";
    private static final String EVENT_TOKEN_ERROR = "registrationError";

    public void load() {
        notificationManager = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        huaweiMessagingService = new MessagingService();

        staticBridge = this.bridge;
        if (lastMessage != null) {
            fireNotification(lastMessage);
            lastMessage = null;
        }

        notificationChannelManager = new NotificationChannelManager(getActivity(), notificationManager);
    }

    @Override
    protected void handleOnNewIntent(Intent data) {
        super.handleOnNewIntent(data);
        Bundle bundle = data.getExtras();
        if (bundle != null && bundle.containsKey("msgId")) {
            JSObject notificationJson = new JSObject();
            JSObject dataObject = new JSObject();
            for (String key : bundle.keySet()) {
                if (key.equals("msgId")) {
                    notificationJson.put("id", bundle.get(key));
                } else {
                    Object value = bundle.get(key);
                    String valueStr = (value != null) ? value.toString() : null;
                    dataObject.put(key, valueStr);
                }
            }
            notificationJson.put("data", dataObject);
            JSObject actionJson = new JSObject();
            actionJson.put("actionId", "tap");
            actionJson.put("notification", notificationJson);
            notifyListeners("pushNotificationActionPerformed", actionJson, true);
        }
    }

    @PluginMethod
    public void register(PluginCall call) {
        Context context = getContext();
        String appId = AGConnectInstance.getInstance().getOptions().getString(PushNotificationsPlugin.CLIENT_APP_ID);
        HmsMessaging.getInstance(context).setAutoInitEnabled(true);

        try {
            String token = HmsInstanceId.getInstance(context).getToken(appId, PushNotificationsPlugin.HCM);
            sendToken(token);
        } catch (ApiException e) {
            sendError(e.getLocalizedMessage());
        }
        call.resolve();
    }

    @PluginMethod
    public void getDeliveredNotifications(PluginCall call) {
        JSArray notifications = new JSArray();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();

            for (StatusBarNotification notif : activeNotifications) {
                JSObject jsNotif = new JSObject();

                jsNotif.put("id", notif.getId());

                Notification notification = notif.getNotification();
                if (notification != null) {
                    jsNotif.put("title", notification.extras.getCharSequence(Notification.EXTRA_TITLE));
                    jsNotif.put("body", notification.extras.getCharSequence(Notification.EXTRA_TEXT));
                    jsNotif.put("group", notification.getGroup());
                    jsNotif.put("groupSummary", 0 != (notification.flags & Notification.FLAG_GROUP_SUMMARY));

                    JSObject extras = new JSObject();

                    for (String key : notification.extras.keySet()) {
                        extras.put(key, notification.extras.get(key));
                    }

                    jsNotif.put("data", extras);
                }

                notifications.put(jsNotif);
            }
        }

        JSObject result = new JSObject();
        result.put("notifications", notifications);
        call.resolve(result);
    }

    @PluginMethod
    public void removeDeliveredNotifications(PluginCall call) {
        JSArray notifications = call.getArray("notifications");

        List<Integer> ids = new ArrayList<>();
        try {
            for (Object o : notifications.toList()) {
                if (o instanceof JSONObject) {
                    JSObject notif = JSObject.fromJSONObject((JSONObject) o);
                    Integer id = notif.getInteger("id");
                    ids.add(id);
                } else {
                    call.reject("Expected notifications to be a list of notification objects");
                }
            }
        } catch (JSONException e) {
            call.reject(e.getMessage());
        }

        for (int id : ids) {
            notificationManager.cancel(id);
        }

        call.resolve();
    }

    @PluginMethod
    public void removeAllDeliveredNotifications(PluginCall call) {
        notificationManager.cancelAll();
        call.resolve();
    }

    @PluginMethod
    public void createChannel(PluginCall call) {
        notificationChannelManager.createChannel(call);
    }

    @PluginMethod
    public void deleteChannel(PluginCall call) {
        notificationChannelManager.deleteChannel(call);
    }

    @PluginMethod
    public void listChannels(PluginCall call) {
        notificationChannelManager.listChannels(call);
    }

    public void sendToken(String token) {
        JSObject data = new JSObject();
        data.put("value", token);
        notifyListeners(EVENT_TOKEN_CHANGE, data, true);
    }

    public void sendError(String error) {
        JSObject data = new JSObject();
        data.put("error", error);
        notifyListeners(EVENT_TOKEN_ERROR, data, true);
    }

    public static void onNewToken(String newToken) {
        PushNotificationsPlugin pushPlugin = PushNotificationsPlugin.getPushNotificationsInstance();
        if (pushPlugin != null) {
            pushPlugin.sendToken(newToken);
        }
    }

    public static void sendRemoteMessage(RemoteMessage remoteMessage, Context context) {
        PushNotificationsPlugin pushPlugin = PushNotificationsPlugin.getPushNotificationsInstance();
        if (pushPlugin != null) {
            pushPlugin.fireNotification(remoteMessage);
        } else {
            PushNotificationsPlugin.scheduleBackground(remoteMessage, context);
        }
    }

    public void fireNotification(RemoteMessage remoteMessage) {
        JSObject remoteMessageData = PushNotificationsPlugin.generateRemoteMessageData(remoteMessage);
        Log.i("HMS", "notifyListeners pushNotificationReceived");

        notifyListeners("pushNotificationReceived", remoteMessageData, true);
    }

    public static JSObject generateRemoteMessageData(RemoteMessage remoteMessage) {
        JSObject remoteMessageData = new JSObject();

        JSObject data = new JSObject();
        remoteMessageData.put("id", remoteMessage.getMessageId());
        for (String key : remoteMessage.getDataOfMap().keySet()) {
            Object value = remoteMessage.getDataOfMap().get(key);
            data.put(key, value);
        }
        remoteMessageData.put("data", data);

        RemoteMessage.Notification notification = remoteMessage.getNotification();
        if (notification != null) {
            remoteMessageData.put("title", notification.getTitle());
            remoteMessageData.put("body", notification.getBody());
            remoteMessageData.put("click_action", notification.getClickAction());

            Uri link = notification.getLink();
            if (link != null) {
                remoteMessageData.put("link", link.toString());
            }
        }
        return remoteMessageData;
    }

    public static PushNotificationsPlugin getPushNotificationsInstance() {
        if (staticBridge != null && staticBridge.getWebView() != null) {
            PluginHandle handle = staticBridge.getPlugin("PushNotifications");
            if (handle == null) {
                return null;
            }
            return (PushNotificationsPlugin) handle.getInstance();
        }
        return null;
    }

    public static void scheduleBackground(RemoteMessage remoteMessage, Context context) {
        Log.i("HMS", "scheduleBackground");
        NotificationStorage notificationStorage = new NotificationStorage(context);
        CapConfig config = CapConfig.loadDefault(context);
        LocalNotificationManager localNotificationManager = new LocalNotificationManager(notificationStorage, null, context, config);

        JSObject remoteMessageData = PushNotificationsPlugin.generateRemoteMessageData(remoteMessage);
        JSObject data = remoteMessageData.getJSObject("data");;

        if (data == null) {
            return;
        }

        JSArray jsArray = new JSArray();
        JSObject notification = new JSObject();
        notification.put("id", data.getInteger("notification_id"));
        notification.put("title", data.getString("title"));
        notification.put("body", data.getString("body"));
        notification.put("autoCancel", true);
        notification.put("extra", data);


        jsArray.put(notification);
        List<LocalNotification> localNotifications = PushNotificationsPlugin.buildNotificationList(jsArray);

        if (localNotifications == null) {
            return;
        }
        JSONArray ids = localNotificationManager.schedule(null, localNotifications);
        if (ids != null) {
            notificationStorage.appendNotifications(localNotifications);
        }

    }

    /**
     * Build list of the notifications from remote plugin call
     */
    public static List<LocalNotification> buildNotificationList(JSArray notificationArray) {
        if (notificationArray == null) {
            return null;
        }
        List<LocalNotification> resultLocalNotifications = new ArrayList<>(notificationArray.length());
        List<JSONObject> notificationsJson;
        try {
            notificationsJson = notificationArray.toList();
        } catch (JSONException e) {
            return null;
        }

        for (JSONObject jsonNotification : notificationsJson) {
            JSObject notification = null;
            try {
                notification = JSObject.fromJSONObject(jsonNotification);
            } catch (JSONException e) {
                return null;
            }

            try {
                LocalNotification activeLocalNotification = LocalNotification.buildNotificationFromJSObject(notification);
                resultLocalNotifications.add(activeLocalNotification);
            } catch (ParseException e) {
                return null;
            }
        }
        return resultLocalNotifications;
    }
}
