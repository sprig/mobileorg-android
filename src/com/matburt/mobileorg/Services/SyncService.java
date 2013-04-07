package com.matburt.mobileorg.Services;

import java.util.ArrayList;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.matburt.mobileorg.R;
import com.matburt.mobileorg.Gui.SynchronizerNotification;
import com.matburt.mobileorg.Gui.SynchronizerNotificationCompat;
import com.matburt.mobileorg.OrgData.MobileOrgApplication;
import com.matburt.mobileorg.OrgData.OrgDatabase;
import com.matburt.mobileorg.OrgData.OrgFileParser;
import com.matburt.mobileorg.Synchronizers.DropboxSynchronizer;
import com.matburt.mobileorg.Synchronizers.NullSynchronizer;
import com.matburt.mobileorg.Synchronizers.SDCardSynchronizer;
import com.matburt.mobileorg.Synchronizers.SSHSynchronizer;
import com.matburt.mobileorg.Synchronizers.Synchronizer;
import com.matburt.mobileorg.Synchronizers.SynchronizerInterface;
import com.matburt.mobileorg.Synchronizers.UbuntuOneSynchronizer;
import com.matburt.mobileorg.Synchronizers.WebDAVSynchronizer;

public class SyncService extends Service implements
		SharedPreferences.OnSharedPreferenceChangeListener {
	private static final String ACTION = "action";
	private static final String START_ALARM = "START_ALARM";
	private static final String STOP_ALARM = "STOP_ALARM";
	private static final String STOP_SYNC = "STOP_SYNC";

	private SharedPreferences appSettings;
	private MobileOrgApplication appInst;

	private AlarmManager alarmManager;
	private PendingIntent autoSyncIntent;
	private PendingIntent syncStopIntent;
	private boolean autoSyncAlarmScheduled = false;

	private boolean syncRunning;
	private Thread syncThread;

	@Override
	public void onCreate() {
		super.onCreate();
		this.appSettings = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext());
		this.appSettings.registerOnSharedPreferenceChangeListener(this);
		this.appInst = (MobileOrgApplication) this.getApplication();
		this.alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
	}

	@Override
	public void onDestroy() {
		unsetAutoSyncAlarm();
		this.appSettings.unregisterOnSharedPreferenceChangeListener(this);
		super.onDestroy();
	}
	
	public static void stopAutoSyncAlarm(Context context) {
		Intent intent = new Intent(context, SyncService.class);
		intent.putExtra(ACTION, SyncService.STOP_ALARM);
		context.startService(intent);
	}

	public static void startAutoSyncAlarm(Context context) {
		Intent intent = new Intent(context, SyncService.class);
		intent.putExtra(ACTION, SyncService.START_ALARM);
		context.startService(intent);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		String action = intent.getStringExtra(ACTION);
		if (action != null && action.equals(START_ALARM))
			setAutoSyncAlarm();
		else if (action != null && action.equals(STOP_ALARM))
			unsetAutoSyncAlarm();
		else if (action != null && action.equals(STOP_SYNC))
			stopSynchronizer();
		else if(!this.syncRunning) {
			this.syncRunning = true;
			runSynchronizer();
		}
		return 0;
	}

    public Synchronizer getSynchronizer() {
        SynchronizerInterface synchronizer = null;
		String syncSource = appSettings.getString("syncSource", "");
		Context c = getApplicationContext();
		
		if (syncSource.equals("webdav"))
			synchronizer =new WebDAVSynchronizer(c);
		else if (syncSource.equals("sdcard"))
			synchronizer = new SDCardSynchronizer(c);
		else if (syncSource.equals("dropbox"))
			synchronizer = new DropboxSynchronizer(c);
        else if (syncSource.equals("ubuntu")) {
            synchronizer = new UbuntuOneSynchronizer(c);
            ((UbuntuOneSynchronizer)synchronizer).getBaseUser();
        }
		else if (syncSource.equals("scp"))
			synchronizer = new SSHSynchronizer(c);
        else if (syncSource.equals("null"))
            synchronizer = new NullSynchronizer();
		else
			synchronizer = null;
		
		SynchronizerNotificationCompat notification;
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH)
			notification = new SynchronizerNotification(this);
		else
			notification = new SynchronizerNotificationCompat(this);
		
		return new Synchronizer(c, synchronizer, notification);
    }

	private void runSynchronizer() {
		unsetAutoSyncAlarm();
		final Synchronizer synchronizer = this.getSynchronizer();
		final OrgDatabase db = new OrgDatabase(this);
		final OrgFileParser parser = new OrgFileParser(db, getContentResolver());
		final boolean calendarEnabled = appSettings.getBoolean("calendarEnabled", false);

		this.syncThread = new Thread() {
			public void run() {
				Log.d("MobileOrg", "Thread - run()");
				ArrayList<String> changedFiles = synchronizer.runSynchronizer(parser);		
				String[] files = changedFiles.toArray(new String[changedFiles.size()]);
				
				if(calendarEnabled) {
					Intent calIntent = new Intent(getBaseContext(), CalendarSyncService.class);
					calIntent.putExtra(CalendarSyncService.PUSH, true);
					calIntent.putExtra(CalendarSyncService.FILELIST, files);
					getBaseContext().startService(calIntent);
				}
				synchronizer.close();
				db.close();
				syncRunning = false;
				cancelSyncTimeout();
				setAutoSyncAlarm();
				Log.d("MobileOrg", "Thread - run() finished");
			}
		};

		setSyncTimeout();
		syncThread.start();
	}

	private void stopSynchronizer() {
		Log.d("MobileOrg", "Stop synchronizer()");
		cancelSyncTimeout();
		
		if (this.syncThread == null)
			return;

		Log.d("MobileOrg", "Try killing thread");
		this.syncThread.interrupt();
		this.syncThread = null;
		Log.d("MobileOrg", "Killed thread!");
	}
	
	private void setSyncTimeout() {
		int timeoutInSeconds = Integer.parseInt(
				this.appSettings.getString(getString(R.string.key_syncTimeout), "60"), 10);
		int timeoutInMillis = timeoutInSeconds * 1000;
		
		Intent intent = new Intent(this, SyncService.class);
		intent.putExtra(ACTION, STOP_SYNC);
		this.syncStopIntent = PendingIntent.getService(appInst, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
		alarmManager.set(AlarmManager.RTC, System.currentTimeMillis()
				+ timeoutInMillis, this.syncStopIntent);
	}
	
	private void cancelSyncTimeout() {
		if (this.syncStopIntent == null)
			return;
		
		alarmManager.cancel(syncStopIntent);
		this.syncStopIntent = null;
	}

	private void setAutoSyncAlarm() {
		boolean doAutoSync = this.appSettings.getBoolean("doAutoSync", false);
		if (!this.autoSyncAlarmScheduled && doAutoSync) {

			int interval = Integer.parseInt(
					this.appSettings.getString("autoSyncInterval", "1800000"),
					10);

			this.autoSyncIntent = PendingIntent.getService(appInst, 0, new Intent(
					this, SyncService.class), 0);
			alarmManager.setRepeating(AlarmManager.RTC,
					System.currentTimeMillis() + interval, interval,
					autoSyncIntent);

			this.autoSyncAlarmScheduled = true;
		}
	}

	private void unsetAutoSyncAlarm() {
		if (this.autoSyncAlarmScheduled) {
			this.alarmManager.cancel(this.autoSyncIntent);
			this.autoSyncAlarmScheduled = false;
		}
	}

	private void resetAutoSyncAlarm() {
		unsetAutoSyncAlarm();
		setAutoSyncAlarm();
	}


	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
			String key) {
		if (key.equals("doAutoSync")) {
			if (sharedPreferences.getBoolean("doAutoSync", false)
					&& !this.autoSyncAlarmScheduled)
				setAutoSyncAlarm();
			else if (!sharedPreferences.getBoolean("doAutoSync", false)
					&& this.autoSyncAlarmScheduled)
				unsetAutoSyncAlarm();
		} else if (key.equals("autoSyncInterval"))
			resetAutoSyncAlarm();
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
