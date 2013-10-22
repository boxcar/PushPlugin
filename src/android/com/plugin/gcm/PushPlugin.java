package com.plugin.gcm;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import com.google.android.gcm.GCMRegistrar;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * @author awysocki
 */

public class PushPlugin extends CordovaPlugin {
	public static final String TAG = "PushPlugin";

	public static final String REGISTER = "register";
	public static final String UNREGISTER = "unregister";
	public static final String EXIT = "exit";

    public static final String PREFERENCES_NAME = "com.plugin.gcm";
    public static final String MESSAGE_NAME_FIELD = "messageName";
    public static final String MSGCNT_NAME_FIELD = "msgcntName";
    public static final String DELIVER_ALL_PUSHES = "deliverAllPushes";
    public static final String SAVED_PUSHES = "savedPushes";

	private static CordovaWebView gWebView;
	private static String gECB;
	private static String gSenderID;
    private static boolean gForeground = true;

	/**
	 * Gets the application context from cordova's main activity.
	 * @return the application context
	 */
	private Context getApplicationContext() {
		return this.cordova.getActivity().getApplicationContext();
	}

	@Override
	public boolean execute(String action, JSONArray data, CallbackContext callbackContext) {

		boolean result = false;

		Log.v(TAG, "execute: action=" + action);

		if (REGISTER.equals(action)) {

			Log.v(TAG, "execute: data=" + data.toString());

			try {
				JSONObject jo = data.getJSONObject(0);
                String messageField, msgcntField;
                boolean deliverAllPushes;

				gWebView = this.webView;
				Log.v(TAG, "execute: jo=" + jo.toString());

				gECB = (String) jo.get("ecb");
				gSenderID = (String) jo.get("senderID");

				try {
					messageField = jo.getString("messageField");
				} catch (JSONException ex) {
					messageField = "message";
				}

                try {
                    msgcntField = jo.getString("msgcntField");
                } catch (JSONException ex) {
                    msgcntField = "msgcnt";
                }

                try {
                    deliverAllPushes = jo.getBoolean("deliverAllPushes");
                } catch (JSONException ex) {
                    deliverAllPushes = false;
                }

                setFieldNames(messageField, msgcntField, deliverAllPushes);

				Log.v(TAG, "execute: ECB=" + gECB + " senderID=" + gSenderID);

				GCMRegistrar.register(getApplicationContext(), gSenderID);
				result = true;
				callbackContext.success();
			} catch (JSONException e) {
				Log.e(TAG, "execute: Got JSON Exception " + e.getMessage());
				result = false;
				callbackContext.error(e.getMessage());
			}

            sendSavedPushes();
		} else if (UNREGISTER.equals(action)) {

			GCMRegistrar.unregister(getApplicationContext());

			Log.v(TAG, "UNREGISTER");
			result = true;
			callbackContext.success();
		} else {
			result = false;
			Log.e(TAG, "Invalid action : " + action);
			callbackContext.error("Invalid action : " + action);
		}

		return result;
	}

	/*
	 * Sends a json object to the client as parameter to a method which is defined in gECB.
	 */
	public static void sendJavascript(JSONObject _json) {
		String _d = "javascript:" + gECB + "(" + _json.toString() + ")";
		Log.v(TAG, "sendJavascript: " + _d);

		if (gECB != null && gWebView != null) {
			gWebView.sendJavascript(_d);
		}
	}

	/*
	 * Sends the pushbundle extras to the client application.
	 * If the client application isn't currently active, it is cached for later processing.
	 */
    public static void sendPush(Context context, Bundle extras) {
        JSONObject json = convertBundleToJson(extras);

			extras.putBoolean("foreground", gForeground);
        if (gECB != null && gWebView != null) {
            sendJavascript(json);
        } else {
            SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            JSONArray savedPushes = getSavedPushes(context);

            try {
                // Bundles with coldstart set are duplicates of last push but with coldstart flag set
                if (json.getBoolean("coldstart"))
                    savedPushes.put(savedPushes.length()-1, json);
                else
                    savedPushes.put(json);
            } catch (JSONException ex) {
                savedPushes.put(json);

            }

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(SAVED_PUSHES, savedPushes.toString());
            editor.commit();
        }
    }

    private static JSONArray getSavedPushes(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);

        try {
            return new JSONArray(prefs.getString(SAVED_PUSHES, "[]"));
        } catch (JSONException ex) {
            return new JSONArray();
        }
    }

    private void sendSavedPushes() {
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        JSONArray savedPushes = getSavedPushes(context);

        for (int i = 0; i < savedPushes.length(); i++) {
            try {
                sendJavascript(savedPushes.getJSONObject(i));
            } catch (JSONException ex) {
            }
        }

        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(SAVED_PUSHES);
        editor.commit();
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        gForeground = true;
    }

	@Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        gForeground = false;
        final NotificationManager notificationManager = (NotificationManager) cordova.getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        gForeground = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        gForeground = false;
		gECB = null;
		gWebView = null;
    }

    /*
     * serializes a bundle to JSON.
     */
	private static JSONObject convertBundleToJson(Bundle extras)
	{
		try
		{
			JSONObject json;
			json = new JSONObject().put("event", "message");

			JSONObject jsondata = new JSONObject();
			Iterator<String> it = extras.keySet().iterator();
			while (it.hasNext())
			{
				String key = it.next();
				Object value = extras.get(key);

				// System data from Android
				if (key.equals("from") || key.equals("collapse_key"))
				{
					json.put(key, value);
				}
				else if (key.equals("foreground"))
				{
					json.put(key, extras.getBoolean("foreground"));
				}
				else if (key.equals("coldstart"))
				{
					json.put(key, extras.getBoolean("coldstart"));
				}
				else
				{
					// Maintain backwards compatibility
					if (key.equals("message") || key.equals("msgcnt") || key.equals("soundname"))
					{
						json.put(key, value);
					}

					if ( value instanceof String ) {
					// Try to figure out if the value is another JSON object

						String strValue = (String)value;
						if (strValue.startsWith("{")) {
							try {
								JSONObject json2 = new JSONObject(strValue);
								jsondata.put(key, json2);
							}
							catch (Exception e) {
								jsondata.put(key, value);
							}
							// Try to figure out if the value is another JSON array
						}
						else if (strValue.startsWith("["))
						{
							try
							{
								JSONArray json2 = new JSONArray(strValue);
								jsondata.put(key, json2);
							}
							catch (Exception e)
							{
								jsondata.put(key, value);
							}
						}
						else
						{
							jsondata.put(key, value);
						}
					}
				}
			} // while
			json.put("payload", jsondata);

			Log.v(TAG, "extrasToJSON: " + json.toString());

			return json;
		}
		catch( JSONException e)
		{
			Log.e(TAG, "extrasToJSON: JSON exception");
		}
		return null;
    }

    public static boolean isInForeground()
    {
      return gForeground;
    }

    private void setFieldNames(String messageName, String msgcntName, boolean deliverAllPushes)
    {
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(MESSAGE_NAME_FIELD, messageName);
        editor.putString(MSGCNT_NAME_FIELD, msgcntName);
        editor.putBoolean(DELIVER_ALL_PUSHES, deliverAllPushes);
        editor.commit();
    }

    public static boolean isActive()
	{
		return gWebView != null;
	}
}
