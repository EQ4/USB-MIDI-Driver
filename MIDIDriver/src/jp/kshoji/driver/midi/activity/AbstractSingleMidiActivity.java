package jp.kshoji.driver.midi.activity;

import android.app.Activity;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.List;
import java.util.Set;

import jp.kshoji.driver.midi.device.MidiInputDevice;
import jp.kshoji.driver.midi.device.MidiOutputDevice;
import jp.kshoji.driver.midi.listener.OnMidiDeviceAttachedListener;
import jp.kshoji.driver.midi.listener.OnMidiDeviceDetachedListener;
import jp.kshoji.driver.midi.listener.OnMidiInputEventListener;
import jp.kshoji.driver.midi.thread.MidiDeviceConnectionWatcher;
import jp.kshoji.driver.midi.util.Constants;
import jp.kshoji.driver.midi.util.UsbMidiDeviceUtils;
import jp.kshoji.driver.usb.util.DeviceFilter;

/**
 * base Activity for using USB MIDI interface.
 * In this implement, the only one device (connected at first) will be detected.
 * launchMode must be "singleTask" or "singleInstance".
 * 
 * @author K.Shoji
 */
public abstract class AbstractSingleMidiActivity extends Activity implements OnMidiDeviceDetachedListener, OnMidiDeviceAttachedListener, OnMidiInputEventListener {
	/**
	 * Implementation for single device connections.
	 * 
	 * @author K.Shoji
	 */
	final class OnMidiDeviceAttachedListenerImpl implements OnMidiDeviceAttachedListener {
		private final UsbManager usbManager;
		
		/**
		 * constructor
		 * 
		 * @param usbManager the UsbManager
		 */
		public OnMidiDeviceAttachedListenerImpl(@NonNull UsbManager usbManager) {
			this.usbManager = usbManager;
		}
		
		@Override
		public synchronized void onDeviceAttached(@NonNull final UsbDevice attachedDevice) {
			if (device != null) {
				// already one device has been connected
				return;
			}

			deviceConnection = usbManager.openDevice(attachedDevice);
			if (deviceConnection == null) {
				return;
			}
			
			List<DeviceFilter> deviceFilters = DeviceFilter.getDeviceFilters(getApplicationContext());

			Set<MidiInputDevice> foundInputDevices = UsbMidiDeviceUtils.findMidiInputDevices(attachedDevice, deviceConnection, deviceFilters, AbstractSingleMidiActivity.this);
			if (foundInputDevices.size() > 0) {
				midiInputDevice = (MidiInputDevice) foundInputDevices.toArray()[0];
			}
			
			Set<MidiOutputDevice> foundOutputDevices = UsbMidiDeviceUtils.findMidiOutputDevices(attachedDevice, deviceConnection, deviceFilters);
			if (foundOutputDevices.size() > 0) {
				midiOutputDevice = (MidiOutputDevice) foundOutputDevices.toArray()[0];
			}
			
			Log.d(Constants.TAG, "Device " + attachedDevice.getDeviceName() + " has been attached.");
			
			AbstractSingleMidiActivity.this.onDeviceAttached(attachedDevice);
		}
	}
	
	/**
	 * Implementation for single device connections.
	 * 
	 * @author K.Shoji
	 */
	final class OnMidiDeviceDetachedListenerImpl implements OnMidiDeviceDetachedListener {

		@Override
		public synchronized void onDeviceDetached(@NonNull final UsbDevice detachedDevice) {
			
			AsyncTask<UsbDevice, Void, Void> task = new AsyncTask<UsbDevice, Void, Void>() {

				@Override
				protected Void doInBackground(UsbDevice... params) {
					if (params == null || params.length < 1) {
						return null;
					}
					
					UsbDevice usbDevice = params[0];
					
					if (midiInputDevice != null) {
						midiInputDevice.stop();
						midiInputDevice = null;
					}
					
					if (midiOutputDevice != null) {
						midiOutputDevice.stop();
						midiOutputDevice = null;
					}
					
					if (deviceConnection != null) {
						deviceConnection.close();
						deviceConnection = null;
					}
					device = null;
					
					Log.d(Constants.TAG, "Device " + usbDevice.getDeviceName() + " has been detached.");
					
					Message message = Message.obtain(deviceDetachedHandler);
					message.obj = usbDevice;
					deviceDetachedHandler.sendMessage(message);
					return null;
				}
			};
			task.execute(detachedDevice);
		}
	}
	
	UsbDevice device = null;
	UsbDeviceConnection deviceConnection = null;
	MidiInputDevice midiInputDevice = null;
	MidiOutputDevice midiOutputDevice = null;
	OnMidiDeviceAttachedListener deviceAttachedListener = null;
	OnMidiDeviceDetachedListener deviceDetachedListener = null;
	Handler deviceDetachedHandler = null;
	private MidiDeviceConnectionWatcher deviceConnectionWatcher = null;

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		UsbManager usbManager = (UsbManager) getApplicationContext().getSystemService(Context.USB_SERVICE);
		deviceAttachedListener = new OnMidiDeviceAttachedListenerImpl(usbManager);
		deviceDetachedListener = new OnMidiDeviceDetachedListenerImpl(); 
		
		deviceDetachedHandler = new Handler(new Callback() {

			@Override
			public boolean handleMessage(Message msg) {
				UsbDevice usbDevice = (UsbDevice) msg.obj;
				onDeviceDetached(usbDevice);
				return true;
			}
		});

		deviceConnectionWatcher = new MidiDeviceConnectionWatcher(getApplicationContext(), usbManager, deviceAttachedListener, deviceDetachedListener);
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		
		if (deviceConnectionWatcher != null) {
			deviceConnectionWatcher.stop();
		}
		deviceConnectionWatcher = null;
		
		if (midiInputDevice != null) {
			midiInputDevice.stop();
			midiInputDevice = null;
		}
		
		midiOutputDevice = null;
		
		deviceConnection = null;
	}


	/**
	 * Suspends receiving/transmitting MIDI messages.
	 * All events will be discarded until the devices being resumed.
	 */
	protected final void suspendMidiDevices() {
		if (midiInputDevice != null) {
			midiInputDevice.suspend();
		}
		
		if (midiOutputDevice != null) {
			midiOutputDevice.suspend();
		}
	}
	
	/**
	 * Resumes from {@link #suspendMidiDevices()}
	 */
	protected final void resumeMidiDevices() {
		if (midiInputDevice != null) {
			midiInputDevice.resume();
		}
		
		if (midiOutputDevice != null) {
			midiOutputDevice.resume();
		}
	}

	/**
	 * Get MIDI output device, if available.
	 * 
	 * @return MidiOutputDevice, null if not available
	 */
    @Nullable
    public final MidiOutputDevice getMidiOutputDevice() {
		if (deviceConnectionWatcher != null) {
			deviceConnectionWatcher.checkConnectedDevicesImmediately();
		}
		
		return midiOutputDevice;
	}
	
	/**
	 * RPN message
	 * This method is just the utility method, do not need to be implemented necessarily by subclass.
	 *
     * @param sender the Object which the event sent
     * @param cable the cable ID 0-15
     * @param channel the MIDI channel number 0-15
	 * @param function 14bits
	 * @param valueMSB higher 7bits
	 * @param valueLSB lower 7bits. -1 if value has no LSB. If you know the function's parameter value have LSB, you must ignore when valueLSB < 0.
	 */
	@Override
	public void onMidiRPNReceived(@NonNull MidiInputDevice sender, int cable, int channel, int function, int valueMSB, int valueLSB) {
		// do nothing in this implementation
	}
	
	/**
	 * NRPN message
	 * This method is just the utility method, do not need to be implemented necessarily by subclass.
	 *
     * @param sender the Object which the event sent
     * @param cable the cable ID 0-15
     * @param channel the MIDI channel number 0-15
	 * @param function 14bits
	 * @param valueMSB higher 7bits
	 * @param valueLSB lower 7bits. -1 if value has no LSB. If you know the function's parameter value have LSB, you must ignore when valueLSB < 0.
	 */
	@Override
	public void onMidiNRPNReceived(@NonNull MidiInputDevice sender, int cable, int channel, int function, int valueMSB, int valueLSB) {
		// do nothing in this implementation
	}
}
