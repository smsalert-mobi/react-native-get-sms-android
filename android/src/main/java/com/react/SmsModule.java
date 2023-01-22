
package com.react;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

import android.content.ContentValues;
import android.os.Bundle;
import android.os.Build;

import android.widget.Toast;
import android.app.LoaderManager;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.ContentResolver;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;

import androidx.annotation.Nullable;

import android.telephony.SmsManager;
import android.telephony.SmsMessage;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

import java.util.Date;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SmsModule extends ReactContextBaseJavaModule /*implements LoaderManager.LoaderCallbacks<Cursor>*/ {
    //    private LoaderManager mManager;
    private Cursor smsCursor;
    private Map<Long, String> smsList;
    private Map<Long, Object> smsListBody;
    Activity mActivity = null;
    private static Context context;
    private ReactContext mReactContext;
    private Callback cb_autoSend_succ = null;
    private Callback cb_autoSend_err = null;
    private static final String SENT = "SMS_SENT";
    private static final String DELIVERED = "SMS_DELIVERED";

    private static final String TAG = "GetSmsAndroid";

    public SmsModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mReactContext = reactContext;
        smsList = new HashMap<Long, String>();
        context = reactContext.getApplicationContext();
        registerBroadcastReceivers();
    }

    @Override
    public String getName() {
        return "Sms";
    }

    @ReactMethod
    public void list(String filter, final Callback errorCallback, final Callback successCallback) {
        try {
            JSONObject filterJ = new JSONObject(filter);
            String uri_filter = filterJ.has("box") ? filterJ.optString("box") : "inbox";
            int fread = filterJ.has("read") ? filterJ.optInt("read") : -1;
            int fid = filterJ.has("_id") ? filterJ.optInt("_id") : -1;
            int ftid = filterJ.has("thread_id") ? filterJ.optInt("thread_id") : -1;
            String faddress = filterJ.optString("address");
            String fcontent = filterJ.optString("body");
            String fContentRegex = filterJ.optString("bodyRegex");
            int indexFrom = filterJ.has("indexFrom") ? filterJ.optInt("indexFrom") : 0;
            int maxCount = filterJ.has("maxCount") ? filterJ.optInt("maxCount") : -1;
            String selection = filterJ.has("selection") ? filterJ.optString("selection") : "";
            String sortOrder = filterJ.has("sortOrder") ? filterJ.optString("sortOrder") : null;
            long maxDate = filterJ.has("maxDate") ? filterJ.optLong("maxDate") : -1;
            long minDate = filterJ.has("minDate") ? filterJ.optLong("minDate") : -1;
            Cursor cursor = context.getContentResolver().query(Uri.parse("content://sms/" + uri_filter), null, selection, null,
                    sortOrder);
            int c = 0;
            JSONArray jsons = new JSONArray();

            while (cursor != null && cursor.moveToNext()) {
                boolean matchFilter = true;
                if (fid > -1)
                    matchFilter = fid == cursor.getInt(cursor.getColumnIndex("_id"));
                else if (ftid > -1)
                    matchFilter = ftid == cursor.getInt(cursor.getColumnIndex("thread_id"));
                else if (fread > -1)
                    matchFilter = fread == cursor.getInt(cursor.getColumnIndex("read"));
                else if (faddress != null && !faddress.isEmpty())
                    matchFilter = faddress.equals(cursor.getString(cursor.getColumnIndex("address")).trim());
                else if (fcontent != null && !fcontent.isEmpty())
                    matchFilter = fcontent.equals(cursor.getString(cursor.getColumnIndex("body")).trim());

                if (fContentRegex != null && !fContentRegex.isEmpty())
                    matchFilter = matchFilter && cursor.getString(cursor.getColumnIndex("body")).matches(fContentRegex);
                if (maxDate > -1)
                    matchFilter = matchFilter && maxDate >= cursor.getLong(cursor.getColumnIndex("date"));
                if (minDate > -1)
                    matchFilter = matchFilter && minDate <= cursor.getLong(cursor.getColumnIndex("date"));
                if (matchFilter) {
                    if (c >= indexFrom) {
                        if (maxCount > 0 && c >= indexFrom + maxCount)
                            break;
                        // Long dateTime = Long.parseLong(cursor.getString(cursor.getColumnIndex("date")));
                        // String message = cursor.getString(cursor.getColumnIndex("body"));
                        JSONObject json;
                        json = getJsonFromCursor(cursor);
                        jsons.put(json);

                    }
                    c++;
                }
            }
            cursor.close();
            try {
                successCallback.invoke(c, jsons.toString());
            } catch (Exception e) {
                errorCallback.invoke(e.getMessage());
            }
        } catch (JSONException e) {
            errorCallback.invoke(e.getMessage());
            return;
        }
    }

    private JSONObject getJsonFromCursor(Cursor cur) {
        JSONObject json = new JSONObject();

        int nCol = cur.getColumnCount();
        String[] keys = cur.getColumnNames();
        try {
            for (int j = 0; j < nCol; j++)
                switch (cur.getType(j)) {
                    case Cursor.FIELD_TYPE_NULL:
                        json.put(keys[j], null);
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        json.put(keys[j], cur.getLong(j));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        json.put(keys[j], cur.getFloat(j));
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                        json.put(keys[j], cur.getString(j));
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        json.put(keys[j], cur.getBlob(j));
                }
        } catch (Exception e) {
            return null;
        }

        return json;
    }

    @ReactMethod
    public void send(String addresses, String text, final Callback errorCallback, final Callback successCallback) {
        mActivity = getCurrentActivity();
        try {
            JSONObject jsonObject = new JSONObject(addresses);
            JSONArray addressList = jsonObject.getJSONArray("addressList");
            int n;
            final int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            if ((n = addressList.length()) > 0) {
                PendingIntent sentIntent = PendingIntent.getBroadcast(mActivity, 0, new Intent("SENDING_SMS"), flag);
                SmsManager sms = SmsManager.getDefault();
                for (int i = 0; i < n; i++) {
                    String address;
                    if ((address = addressList.optString(i)).length() > 0)
                        sms.sendTextMessage(address, null, text, sentIntent, null);
                }
            } else {
                PendingIntent sentIntent = PendingIntent.getActivity(mActivity, 0, new Intent("android.intent.action.VIEW"), flag);
                Intent intent = new Intent("android.intent.action.VIEW");
                intent.putExtra("sms_body", text);
                intent.setData(Uri.parse("sms:"));
                try {
                    sentIntent.send(mActivity.getApplicationContext(), 0, intent);
                    successCallback.invoke("OK");
                } catch (PendingIntent.CanceledException e) {
                    errorCallback.invoke(e.getMessage());
                    return;
                }
            }
            return;
        } catch (JSONException e) {
            errorCallback.invoke(e.getMessage());
            return;
        }

    }

    @ReactMethod
    public void delete(Integer id, final Callback errorCallback, final Callback successCallback) {
        try {
            int res = context.getContentResolver().delete(Uri.parse("content://sms/" + id), null, null);
            if (res > 0) {
                successCallback.invoke("OK");
            } else {
                errorCallback.invoke("SMS not found");
            }
            return;
        } catch (Exception e) {
            errorCallback.invoke(e.getMessage());
            return;
        }
    }

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
    }

    private void sendCallback(String params, String message, boolean success) {
        Log.d(TAG, "sendCallback: " + params);
        if (success && cb_autoSend_succ != null) {
            cb_autoSend_succ.invoke(message, params);
            cb_autoSend_succ = null;
        } else if (!success && cb_autoSend_err != null) {
            cb_autoSend_err.invoke(message, params);
            cb_autoSend_err = null;
        }
    }

    private int getUniqueInt(String params) throws JSONException {
        int i = (int) (new Date().getTime() % 1000);

        JSONObject obj = new JSONObject(params);
        int id = obj.getInt("id");

        return id * 1000 + i;
    }

    private void registerBroadcastReceivers() {
        //---when the SMS has been sent---
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context arg0, Intent arg1) {
                String params = arg1.getStringExtra("params");
                Log.d(TAG, "registerBroadcastReceivers: " + params);
                switch (getResultCode()) {
                    case Activity.RESULT_OK:
                        sendCallback(params, "SMS sent", true);
                        break;
                    case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                        sendCallback(params, "Generic failure", false);
                        break;
                    case SmsManager.RESULT_ERROR_NO_SERVICE:
                        sendCallback(params, "No service", false);
                        break;
                    case SmsManager.RESULT_ERROR_NULL_PDU:
                        sendCallback(params, "Null PDU", false);
                        break;
                    case SmsManager.RESULT_ERROR_RADIO_OFF:
                        sendCallback(params, "Radio off", false);
                        break;
                }
            }
        }, new IntentFilter(SENT));

        //---when the SMS has been delivered---
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context arg0, Intent arg1) {
                String params = arg1.getStringExtra("params");
                WritableMap response = Arguments.createMap();
                response.putString("params", params);
                Log.d(TAG, "registerReceiver: " + params);
                switch (getResultCode()) {
                    case Activity.RESULT_OK:
                        response.putString("result", "SMS delivered");
                        sendEvent(mReactContext, "sms_onDelivery", response);
                        break;
                    case Activity.RESULT_CANCELED:
                        response.putString("result", "SMS not delivered");
                        sendEvent(mReactContext, "sms_onDelivery", response);
                        break;
                }
            }
        }, new IntentFilter(DELIVERED));                                                    
    }

    @ReactMethod
    public void autoSend(String phoneNumber, String message, String params, final Callback errorCallback,
                         final Callback successCallback) {

        cb_autoSend_succ = successCallback;
        cb_autoSend_err = errorCallback;

        try {
            ArrayList<PendingIntent> sentPendingIntents = new ArrayList<PendingIntent>();
            ArrayList<PendingIntent> deliveredPendingIntents = new ArrayList<PendingIntent>();

            int requestCode = getUniqueInt(params);
            final int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;

            Intent sentIntent = new Intent(SENT);
            sentIntent.putExtra("params", params);
            Log.d(TAG, "autoSend#sentIntent.putExtra: " + params);
            PendingIntent sentPI = PendingIntent.getBroadcast(context, requestCode, sentIntent, flag);

            Intent deliveredIntent = new Intent(DELIVERED);
            deliveredIntent.putExtra("params", params);
            Log.d(TAG, "autoSend#deliveredIntent.putExtra: " + params);
            PendingIntent deliveredPI = PendingIntent.getBroadcast(context, requestCode, deliveredIntent, flag);
            
            SmsManager sms = SmsManager.getDefault();
            ArrayList<String> parts = sms.divideMessage(message);

            for (int i = 0; i < parts.size(); i++) {
                sentPendingIntents.add(i, sentPI);
                deliveredPendingIntents.add(i, deliveredPI);
            }
            sms.sendMultipartTextMessage(phoneNumber, null, parts, sentPendingIntents, deliveredPendingIntents);

            ContentValues values = new ContentValues();
            values.put("address", phoneNumber);
            values.put("body", message);
            context.getContentResolver().insert(Uri.parse("content://sms/sent"), values);

        } catch (Exception e) {
            sendCallback(params, e.getMessage(), false);
        }
    }
}
