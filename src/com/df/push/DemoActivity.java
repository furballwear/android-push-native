/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.df.push;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

/**
 * Main UI for the demo app.
 */
public class DemoActivity extends Activity {

	public static final String EXTRA_MESSAGE = "message";
	public static final String PROPERTY_REG_ID = "registration_id";
	public static final String PROPERTY_SUB = "subscriptions";
	public static final String PROPERTY_REG_URL = "registration_url";
	private static final String PROPERTY_APP_VERSION = "appVersion";
	private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;

	/**
	 * Substitute you own sender ID here. This is the project number you got
	 * from the API Console, as described in "Getting Started."
	 */
	String SENDER_ID = "138837205839";

	/**
	 * Tag used on log messages.
	 */
	static final String TAG = "DF GCM Demo";

	TextView mDisplay;
	GoogleCloudMessaging gcm;
	AtomicInteger msgId = new AtomicInteger();
	Context context;

	String regid;
	ProgressDialog progressDialog;
	//	private CharSequence[] topics = {
	//			"Rajesh", "Mahesh", "Vijayakumar"
	//	};

	private ArrayList<String> topics = new ArrayList<String>();
	private List<String> subscriptions = new ArrayList<String>();

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.main);
		mDisplay = (TextView) findViewById(R.id.display);

		context = getApplicationContext();
		progressDialog = new ProgressDialog(DemoActivity.this);
		progressDialog.setMessage("Please wait...");
		// Check device for Play Services APK. If check succeeds, proceed with GCM registration.
		if (checkPlayServices()) {
			// display loading bar and fetch topics for future use
			getTopics();
		} else {
			Log.i(TAG, "No valid Google Play Services APK found.");
		}

		readSubscriptions(); // if subscribed previously
		final SharedPreferences prefs = getGcmPreferences(context);
		if(prefs.getString(PROPERTY_REG_URL, null) == null){
			// device token is not updated on server, disable subscribe and unsubscribe buttons
			findViewById(R.id.subscribe).setVisibility(View.GONE);
			findViewById(R.id.unsubscribe).setVisibility(View.GONE);
		}else{
			findViewById(R.id.subscribe).setVisibility(View.VISIBLE);
			findViewById(R.id.unsubscribe).setVisibility(View.VISIBLE);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Check device for Play Services APK.
		checkPlayServices();
	}

	/**
	 * Check the device to make sure it has the Google Play Services APK. If
	 * it doesn't, display a dialog that allows users to download the APK from
	 * the Google Play Store or enable it in the device's system settings.
	 */
	private boolean checkPlayServices() {
		int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
		if (resultCode != ConnectionResult.SUCCESS) {
			if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
				GooglePlayServicesUtil.getErrorDialog(resultCode, this,
						PLAY_SERVICES_RESOLUTION_REQUEST).show();
			} else {
				Log.i(TAG, "This device is not supported.");
				finish();
			}
			return false;
		}
		return true;
	}

	/**
	 * Stores the registration ID and the app versionCode in the application's
	 * {@code SharedPreferences}.
	 *
	 * @param context application's context.
	 * @param regId registration ID
	 */
	private void storeRegistrationId(Context context, String regId) {
		final SharedPreferences prefs = getGcmPreferences(context);
		int appVersion = getAppVersion(context);
		Log.i(TAG, "Saving regId on app version " + appVersion);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(PROPERTY_REG_ID, regId);
		editor.putInt(PROPERTY_APP_VERSION, appVersion);
		editor.commit();
	}

	/**
	 * Gets the current registration ID for application on GCM service, if there is one.
	 * <p>
	 * If result is empty, the app needs to register.
	 *
	 * @return registration ID, or empty string if there is no existing
	 *         registration ID.
	 */
	private String getRegistrationId(Context context) {
		final SharedPreferences prefs = getGcmPreferences(context);
		String registrationId = prefs.getString(PROPERTY_REG_ID, "");
		if (registrationId.isEmpty()) {
			Log.i(TAG, "Registration not found.");
			return "";
		}
		// Check if app was updated; if so, it must clear the registration ID
		// since the existing regID is not guaranteed to work with the new
		// app version.
		int registeredVersion = prefs.getInt(PROPERTY_APP_VERSION, Integer.MIN_VALUE);
		int currentVersion = getAppVersion(context);
		if (registeredVersion != currentVersion) {
			Log.i(TAG, "App version changed.");
			return "";
		}
		return registrationId;
	}

	/**
	 * Registers the application with GCM servers asynchronously.
	 * <p>
	 * Stores the registration ID and the app versionCode in the application's
	 * shared preferences.
	 */
	private void registerInBackground() {
		new AsyncTask<Void, Void, String>() {
			@Override
			protected String doInBackground(Void... params) {
				String msg = "";
				try {
					if (gcm == null) {
						gcm = GoogleCloudMessaging.getInstance(context);
					}
					regid = gcm.register(SENDER_ID);
					msg = "Device registered, registration ID=" + regid;

					// You should send the registration ID to your server over HTTP, so it
					// can use GCM/HTTP or CCS to send messages to your app.
					sendRegistrationIdToBackend(regid);
					storeRegistrationId(context, regid);
				} catch (IOException ex) {
					msg = "Error :" + ex.getMessage();
					// If there is an error, don't just keep trying to register.
					// Require the user to click a button again, or perform
					// exponential back-off.
				}
				return msg;
			}

			@Override
			protected void onPostExecute(String msg) {
				mDisplay.append(msg + "\n");
				Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
				progressDialog.dismiss();
			}

			@Override
			protected void onPreExecute() {
				if(progressDialog != null)
					progressDialog.show();
			};

		}.execute(null, null, null);
	}

	// Send an upstream message.
	public void onClick(final View view) {

		if (view == findViewById(R.id.register)) {
			// Check device for Play Services APK. If check succeeds, proceed with GCM registration.
			if (checkPlayServices()) {
				gcm = GoogleCloudMessaging.getInstance(this);
				regid = getRegistrationId(context);

				if (regid.isEmpty()) {
					registerInBackground();
				}else{
					sendRegistrationIdToBackend(regid);
				}
			} else {
				Log.i(TAG, "No valid Google Play Services APK found.");
			}
		} else if (view == findViewById(R.id.clear)) {
			mDisplay.setText("");
		} else if (view == findViewById(R.id.subscribe)) {
			displayOptionsToSubscribe();
		} else if (view == findViewById(R.id.unsubscribe)) {
			displayOptionsToUnbscribe();
		}
	}


	private void displayOptionsToSubscribe(){
		final CharSequence[] cs = topics.toArray(new CharSequence[topics.size()]);
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle("Select topic to subscribe");

		builder.setItems(cs, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int item) {
				// Do something with the selection
				final SharedPreferences prefs = getGcmPreferences(context);
				subscribe(cs[item].toString(), prefs.getString(PROPERTY_REG_URL, null));
			}
		});
		AlertDialog alert = builder.create();
		alert.show();
	}

	private void displayOptionsToUnbscribe(){
		if(subscriptions.size() == 0){
			Toast.makeText(getApplicationContext(), "No Subscriptions...", Toast.LENGTH_LONG).show();
			return;
		}
		final CharSequence[] cs = subscriptions.toArray(new CharSequence[subscriptions.size()]);
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle("Select item to unsubscribe");

		builder.setItems(cs, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int item) {
				// Do something with the selection
				unsubscribe(cs[item].toString());
			}
		});
		AlertDialog alert = builder.create();
		alert.show();
	}

	@Override
	protected void onDestroy() {
		saveSubscriptionList();
		super.onDestroy();
	}

	/**
	 * @return Application's version code from the {@code PackageManager}.
	 */
	private static int getAppVersion(Context context) {
		try {
			PackageInfo packageInfo = context.getPackageManager()
					.getPackageInfo(context.getPackageName(), 0);
			return packageInfo.versionCode;
		} catch (NameNotFoundException e) {
			// should never happen
			throw new RuntimeException("Could not get package name: " + e);
		}
	}

	/**
	 * @return Application's {@code SharedPreferences}.
	 */
	private SharedPreferences getGcmPreferences(Context context) {
		// This sample app persists the registration ID in shared preferences, but
		// how you store the regID in your app is up to you.
		return getSharedPreferences(DemoActivity.class.getSimpleName(),
				Context.MODE_PRIVATE);
	}


	/**
	 * Sends the registration ID to your server over HTTP, so it can use GCM/HTTP or CCS to send
	 * messages to your app. Not needed for this demo since the device sends upstream messages
	 * to a server that echoes back the message using the 'from' address in the message.
	 */
	private void sendRegistrationIdToBackend(final String regId) {
		// Dreamfactory server url
		final String url = "https://next.cloud.dreamfactory.com/rest/sns/app/662443008147:app/GCM/com.dreamfactory.launchpad/endpoint?app_name=todoangular";
		new AsyncTask<Void, Void, String>() {
			@Override
			protected String doInBackground(Void... params) {
				String msg = "";
				JSONObject obj = new JSONObject();
				try {
					obj.put("Token", regId);
				} catch (JSONException e1) {
					e1.printStackTrace();
				}
				Log.i(TAG, "Device registration request data " + obj.toString());
				// Your implementation here.
				HttpResponse<JsonNode> jsonResponse;
				try {
					jsonResponse = Unirest.post(url)
							.header("accept", "application/json")
							.body(obj.toString())
							.asJson();
					String endPoint = jsonResponse.getBody().getObject().getString("EndpointArn");
					Log.i(TAG, "Device registration response " + jsonResponse.getBody().getObject().toString());
					msg = null;
					final SharedPreferences prefs = getGcmPreferences(context);
					SharedPreferences.Editor editor = prefs.edit();
					editor.putString(PROPERTY_REG_URL, endPoint);
					editor.commit();
				} catch (Exception e) { 
					msg = e.getLocalizedMessage();
				}
				return msg;
			}

			@Override
			protected void onPostExecute(String msg) {
				if(msg == null){
					findViewById(R.id.subscribe).setVisibility(View.VISIBLE);
					findViewById(R.id.unsubscribe).setVisibility(View.VISIBLE);
				}else{
					mDisplay.append(msg + "\n");
				}
				progressDialog.dismiss();
			}

			@Override
			protected void onPreExecute() {
				if(progressDialog != null)
					progressDialog.show();
			};
		}.execute(null, null, null);
	}

	private void getTopics() {
		final String url = "https://next.cloud.dreamfactory.com/rest/sns/topic?app_name=todoangular";

		new AsyncTask<Void, Void, String>() {
			@Override
			protected String doInBackground(Void... params) {
				String msg = "";
				HttpResponse<JsonNode> jsonResponse;
				try {
					jsonResponse = Unirest.get(url)
							.header("accept", "application/json")
							.asJson();
					JSONArray topicsArray = jsonResponse.getBody().getObject().getJSONArray("resource");
					for(int i=0; i< topicsArray.length(); i++){
						topics.add(topicsArray.getJSONObject(i).getString("Topic"));
					}
					Log.i(TAG, "Get Topic Response " + topics.toString());

				} catch (Exception e) { 
					msg = e.getLocalizedMessage();
				}
				return msg;
			}

			@Override
			protected void onPostExecute(String msg) {
				//				mDisplay.append(msg + "\n")
				progressDialog.dismiss();
			}

			@Override
			protected void onPreExecute() {
				if(progressDialog != null)
					progressDialog.show();
			};
		}.execute(null, null, null);
	}

	private void subscribe(final String topic, final String endPoint) {
		final String url = "https://next.cloud.dreamfactory.com/rest/sns/subscription?app_name=todoangular";

		new AsyncTask<Void, Void, String>() {
			@Override
			protected String doInBackground(Void... params) {
				String msg = "";
				JSONObject obj = new JSONObject();
				try {
					obj.put("Topic", topic);
					obj.put("Protocol", "application");
					obj.put("Endpoint", endPoint);
				} catch (JSONException e1) {
					e1.printStackTrace();
				}

				HttpResponse<JsonNode> jsonResponse;
				try {
					jsonResponse = Unirest.post(url)
							.header("accept", "application/json")
							.body(obj.toString())
							.asJson();
					msg = "Subscribe response " + jsonResponse.getBody().toString();
					subscriptions.add(jsonResponse.getBody().getObject().getString("SubscriptionArn"));
					Log.i(TAG, msg);
				} catch (Exception e) { 
					msg = e.getLocalizedMessage();
				}
				return msg;
			}

			@Override
			protected void onPostExecute(String msg) {
				mDisplay.append(msg + "\n");
				progressDialog.dismiss();
			}

			@Override
			protected void onPreExecute() {
				if(progressDialog != null)
					progressDialog.show();
			};
		}.execute(null, null, null);
	}

	private void unsubscribe(final String topic) {
		final String url = "https://next.cloud.dreamfactory.com/rest/sns/subscription/" + topic + "?app_name=todoangular";

		new AsyncTask<Void, Void, String>() {
			@Override
			protected String doInBackground(Void... params) {
				String msg = "";

				HttpResponse<JsonNode> jsonResponse;
				try {
					jsonResponse = Unirest.delete(url)
							.header("accept", "application/json")
							.asJson();
					msg = "Unsubscribe response " + jsonResponse.getBody().toString();
					Log.i(TAG, msg);
					subscriptions.remove(topic);
				} catch (UnirestException e) { 
					msg = e.getLocalizedMessage();
				}
				return msg;
			}

			@Override
			protected void onPostExecute(String msg) {
				mDisplay.append(msg + "\n");
				progressDialog.dismiss();
				subscriptions.remove(topic);
			}

			@Override
			protected void onPreExecute() {
				if(progressDialog != null)
					progressDialog.show();
			};
		}.execute(null, null, null);
	}

	private void readSubscriptions(){
		final SharedPreferences prefs = getGcmPreferences(context);
		String serialized = prefs.getString(PROPERTY_SUB, null);
		if(serialized != null)
			subscriptions = Arrays.asList(TextUtils.split(serialized, ","));
	}

	private void saveSubscriptionList(){
		final SharedPreferences prefs = getGcmPreferences(context);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(PROPERTY_SUB, TextUtils.join(",", subscriptions));
		editor.commit();
	}
}
