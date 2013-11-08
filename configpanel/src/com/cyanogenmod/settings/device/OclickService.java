package com.cyanogenmod.settings.device;

import java.util.Set;
import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.hardware.input.InputManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.cyanogenmod.settings.device.utils.Constants;

public class OclickService extends Service implements OnSharedPreferenceChangeListener {

    private static final String TAG = OclickService.class.getSimpleName();

    private BluetoothGatt mBluetoothGatt;
    private Handler mHandler;
    boolean mAlerting;

    private BluetoothDevice mOClickDevice;

    private static final UUID sTriggerServiceUUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID sTriggerCharacteristicUUIDv1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID sTriggerCharacteristicUUIDv2 = UUID.fromString("f000ffe1-0451-4000-b000-000000000000");

    private static final UUID sImmediateAlertServiceUUID = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb"); //0-2
    private static final UUID sImmediateAlertCharacteristicUUID = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb");

    private static final UUID sLinkLossServiceUUID = UUID.fromString("00001803-0000-1000-8000-00805f9b34fb"); // 0-3
    private static final UUID sLinkLossCharacteristicUUID = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb");

    private static final UUID sControllCharacteristicUUIDv1 = UUID.fromString("0000aa01-0000-1000-8000-00805f9b34fb");
    private static final UUID sControllCharacteristicUUIDv2 = UUID.fromString("f000ffe2-0451-4000-b000-000000000000");
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void sendCommand(int command) {
        Log.d(TAG, "sendCommand : " + command);
        Intent i = new Intent(BluetoothInputSettings.PROCESS_COMMAND_ACTION);
        i.putExtra(BluetoothInputSettings.COMMAND_KEY, command);
        sendBroadcast(i);
    }

    Handler mRssiPoll = new Handler();
    BluetoothGattCharacteristic mChar;
    private BluetoothGattCallback mGattCallback =
            new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, final int newState) {
            Log.d(TAG, "onConnectionStateChange " + status + " " + newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                mBluetoothGatt = gatt;
                if (mBluetoothGatt.getServices().size() == 0)
                gatt.discoverServices();
            }
            sendCommand(newState);
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {

            Log.d(TAG, "onServicesDiscovered " + status);

            // Register trigger notification
            BluetoothGattService service = gatt.getService(sTriggerServiceUUID);
            BluetoothGattCharacteristic trigger = service.getCharacteristic(sTriggerCharacteristicUUIDv1);
            
            if (trigger == null) {
                trigger = service.getCharacteristic(sTriggerCharacteristicUUIDv2);
            }
            mChar = trigger;
            gatt.setCharacteristicNotification(trigger, true);

            toggleRssiListener();

            boolean alert = Constants.isPreferenceEnabled(getBaseContext(), Constants.OCLICK_DISCONNECT_ALERT_KEY, true);
            service = mBluetoothGatt.getService(sLinkLossServiceUUID);
            trigger = service.getCharacteristic(sLinkLossCharacteristicUUID);
            byte[] value = new byte[1];
            value[0] = (byte) (alert ? 2 : 0);
            trigger.setValue(value);
            mBluetoothGatt.writeCharacteristic(trigger);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic.getService().getUuid().equals(OclickService.sLinkLossServiceUUID)) {
                Log.d(TAG, characteristic.getUuid() + " : " + characteristic.getValue()[0]);
                BluetoothGattService service2 = mBluetoothGatt.getService(sImmediateAlertServiceUUID);
                BluetoothGattCharacteristic trigger2 = service2.getCharacteristic(sImmediateAlertCharacteristicUUID);
                byte[] values = new byte[1];
                values[0] = (byte) 0;
                trigger2.setValue(values);
                mBluetoothGatt.writeCharacteristic(trigger2);
            }
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onReliableWriteCompleted : " + status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.d(TAG, "Characteristic changed " + characteristic.getUuid());
            if (mTapPending) {
                Log.d(TAG, "Executing ring alarm");
                mHandler.removeCallbacks(mSingleTapRunnable);
                Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
                r.play();
                mTapPending = false;
                return;
            }
            Log.d(TAG, "Setting single tap runnable");
            mTapPending = true;
            mHandler.postDelayed(mSingleTapRunnable, 1000);
        }

        
        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            Log.d(TAG, "Rssi value : " + rssi);
            byte[] value = new byte[1];
            BluetoothGattCharacteristic charS = gatt.getService(sImmediateAlertServiceUUID)
                    .getCharacteristic(sImmediateAlertCharacteristicUUID);
            if (rssi < -90 && !mAlerting) {
                value[0] = 2;
                charS.setValue(value);
                mBluetoothGatt.writeCharacteristic(charS);
                mAlerting = true;
            } else if (rssi > -90 && mAlerting) {
                value[0] = 0;
                mAlerting = false;
                charS.setValue(value);
                mBluetoothGatt.writeCharacteristic(charS);
            }
        }

    };

    boolean mTapPending = false;

    private Runnable mSingleTapRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            InputManager im = InputManager.getInstance();
            im.injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_CAMERA, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD), InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
            im.injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,KeyEvent.KEYCODE_CAMERA,
                    0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
                    InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
            mTapPending = false;

        }
    };

    static BluetoothDevice getPairedOclick(BluetoothAdapter adapter) {
        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
        // If there are paired devices
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice pairedDevice : pairedDevices) {
                if (pairedDevice.getBondState() == BluetoothDevice.BOND_BONDED
                        && pairedDevice.getName().toLowerCase().contains("oppo")) {
                    return pairedDevice;
                }
            }
        }
        return null;
    }

    private void toggleRssiListener() {
        boolean fence = Constants.isPreferenceEnabled(getBaseContext(), Constants.OCLICK_FENCE_KEY, true);
        mRssiPoll.removeCallbacksAndMessages(null);
        if (fence) {
            Log.d(TAG, "Enabling rssi listener");
            mRssiPoll.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mBluetoothGatt.readRemoteRssi();
                    mRssiPoll.postDelayed(this, 2000);
                }
            }, 100);
        }
    }

    @Override
    public void onCreate() {
        mHandler = new Handler();
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
            String key) {
        if (key.equals(Constants.OCLICK_FENCE_KEY)) {
            toggleRssiListener();
        } else if (key.equals(Constants.OCLICK_DISCONNECT_ALERT_KEY)) {
            boolean alert = Constants.isPreferenceEnabled(getBaseContext(), Constants.OCLICK_DISCONNECT_ALERT_KEY, true);
            BluetoothGattService service = mBluetoothGatt.getService(sLinkLossServiceUUID);
            BluetoothGattCharacteristic trigger = service.getCharacteristic(sLinkLossCharacteristicUUID);
            byte[] value = new byte[1];
            value[0] = (byte) (alert ? 2 : 0);
            trigger.setValue(value);
            mBluetoothGatt.writeCharacteristic(trigger);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onstartCommand " + intent.getAction());
        if (mBluetoothGatt == null) {
            mOClickDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (mOClickDevice == null) {
                Log.e(TAG, "No bluetooth device provided");
                stopSelf();
            }
            mOClickDevice.connectGatt(getBaseContext(), false, mGattCallback);
        }
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service being killed");
        mRssiPoll.removeCallbacksAndMessages(null);
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
    }
}