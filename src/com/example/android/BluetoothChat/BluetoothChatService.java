/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.example.android.BluetoothChat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for
 * incoming connections, a thread for connecting with a device, and a
 * thread for performing data transmissions when connected.
 */
public class BluetoothChatService {
    // Debugging
    private static final String TAG = "BluetoothChatService";
    private static final boolean D = true;

    // Name for the SDP record when creating server socket
    private static final String NAME_SECURE = "BluetoothChatSecure";
    private static final String NAME_INSECURE = "BluetoothChatInsecure";

    // Unique UUID for this application
    private static final UUID MY_UUID_SECURE =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID MY_UUID_INSECURE =
        UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");

    // Member fields
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mSecureAcceptThread;
    private AcceptThread mInsecureAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    /**
     * Constructor. Prepares a new BluetoothChat session.
     * @param context  The UI Activity Context
     * @param handler  A Handler to send messages back to the UI Activity
     */
    public BluetoothChatService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
    }

    /**
     * Set the current state of the chat connection
     * @param state  An integer defining the current connection state
     */
    private synchronized void setState(int state) {
        if (D) Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;

        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(BluetoothChat.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    /**
     * Return the current connection state. */
    public synchronized int getState() {
        return mState;
    }

    /**
     * Start the chat service. Specifically start AcceptThread to begin a
     * session in listening (server) mode. Called by the Activity onResume() */
    public synchronized void start() {
        if (D) Log.d(TAG, "start");

        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        setState(STATE_LISTEN);

        // Start the thread to listen on a BluetoothServerSocket
        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = new AcceptThread(true);
            mSecureAcceptThread.start();
        }
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = new AcceptThread(false);
            mInsecureAcceptThread.start();
        }
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     * @param device  The BluetoothDevice to connect
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    public synchronized void connect(BluetoothDevice device, boolean secure) {
        if (D) Log.d(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device, secure);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     * @param socket  The BluetoothSocket on which the connection was made
     * @param device  The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice
            device, final String socketType) {
        if (D) Log.d(TAG, "connected, Socket Type:" + socketType);

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Cancel the accept thread because we only want to connect to one device
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket, socketType);
        mConnectedThread.start();

        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(BluetoothChat.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(BluetoothChat.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        setState(STATE_CONNECTED);
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        if (D) Log.d(TAG, "stop");

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }

        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }
        setState(STATE_NONE);
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(BluetoothChat.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(BluetoothChat.TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        // Start the service over to restart listening mode
        BluetoothChatService.this.start();
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(BluetoothChat.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(BluetoothChat.TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        // Start the service over to restart listening mode
        BluetoothChatService.this.start();
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;
        private String mSocketType;

        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            mSocketType = secure ? "Secure":"Insecure";

            // Create a new listening server socket
            try {
                if (secure) {
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE,
                        MY_UUID_SECURE);
                } else {
                    tmp = mAdapter.listenUsingInsecureRfcommWithServiceRecord(
                            NAME_INSECURE, MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "listen() failed", e);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            if (D) Log.d(TAG, "Socket Type: " + mSocketType +
                    "BEGIN mAcceptThread" + this);
            setName("AcceptThread" + mSocketType);

            BluetoothSocket socket = null;

            // Listen to the server socket if we're not connected
            while (mState != STATE_CONNECTED) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "Socket Type: " + mSocketType + "accept() failed", e);
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (BluetoothChatService.this) {
                        switch (mState) {
                        case STATE_LISTEN:
                        case STATE_CONNECTING:
                            // Situation normal. Start the connected thread.
                            connected(socket, socket.getRemoteDevice(),
                                    mSocketType);
                            break;
                        case STATE_NONE:
                        case STATE_CONNECTED:
                            // Either not ready or already connected. Terminate new socket.
                            try {
                                socket.close();
                            } catch (IOException e) {
                                Log.e(TAG, "Could not close unwanted socket", e);
                            }
                            break;
                        }
                    }
                }
            }
            if (D) Log.i(TAG, "END mAcceptThread, socket Type: " + mSocketType);

        }

        public void cancel() {
            if (D) Log.d(TAG, "Socket Type" + mSocketType + "cancel " + this);
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Socket Type" + mSocketType + "close() of server failed", e);
            }
        }
    }


    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device, boolean secure) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                if (secure) {
                    tmp = device.createRfcommSocketToServiceRecord(
                            MY_UUID_SECURE);
                } else {
                    tmp = device.createInsecureRfcommSocketToServiceRecord(
                            MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "create() failed", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectThread SocketType:" + mSocketType);
            setName("ConnectThread" + mSocketType);

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() " + mSocketType +
                            " socket during connection failure", e2);
                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothChatService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice, mSocketType);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect " + mSocketType + " socket failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket, String socketType) {
            Log.d(TAG, "create ConnectedThread: " + socketType);
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);

                    // Send the obtained bytes to the UI Activity
                    mHandler.obtainMessage(BluetoothChat.MESSAGE_READ, bytes, -1, buffer)
                            .sendToTarget();
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    // Start the service over to restart listening mode
                    BluetoothChatService.this.start();
                    break;
                }
            }
        }

        /**
         * Write to the connected OutStream.
         * @param buffer  The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
                if(buffer[0] == (byte)0x9A){
                // Share the sent message back to the UI Activity
                mHandler.obtainMessage(BluetoothChat.MESSAGE_WRITE, -1, -1, buffer)
                        .sendToTarget();}
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }
        public void write(String message) throws IOException {
            byte[] buffer = null;
            
            String buf[] = message.split(" ");
            if(buf[0].equals("start")){ //計測開始
            	if(buf.length >= 2){
            		if(buf[1].matches("^[0-9]{1,3}$")) {	//秒設定がある場合
            			startMeasure(mmOutStream,Integer.parseInt(buf[1]));
            		}else{
                		startMeasure(mmOutStream);	//ない場合
            		}
            	}else{
            		startMeasure(mmOutStream); //ない場合
            	}
            }else if(buf[0].equals("stop")){ //計測停止
            	stopMeasure(mmOutStream);
            }else if(buf[0].equals("setags") && buf.length >= 4){	//加速度角速度センサの設定
            	if(buf[1].matches("^[0-9]{1,3}$")&&buf[2].matches("^[0-9]{1,3}$")&&buf[3].matches("^[0-9]{1,3}$")) {
            		setAccelMeasurement(mmOutStream, Integer.parseInt(buf[1]), Integer.parseInt(buf[2]), Integer.parseInt(buf[3]));
            	}         
            }else if(buf[0].equals("setRange") && buf.length >=2 ){ //加速度レンジ設定
            	if(buf[1].matches("^[0-3]{1}$"))setAccelRange(mmOutStream, Integer.parseInt(buf[1]));
            }else if(buf[0].equals("getbattv")){ //バッテリ残量の確認
            	getVoltage(mmOutStream);
            }else if(buf[0].equals("getd")){ //時刻の確認
            	getTime(mmOutStream);
            }else if(buf[0].equals("bin")){
            	bin(mmOutStream , buf);
            }
            
			//mmOutStream.write();
			/*if(false){
			// Share the sent message back to the UI Activity
			mHandler.obtainMessage(BluetoothChat.MESSAGE_WRITE, -1, -1, buffer)
			        .sendToTarget();}*/
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
  //ここから追加
  //データ送信
  	public void sendDatum(OutputStream out, byte[] sendData){
  		try{
  			out.write(sendData);
  		}catch(IOException e){
  			//fizz
  		}
  		//System.out.println("# send command 0x"+Integer.toHexString(sendData[1]));
/*  		try{
  			out.close();
  		}catch(IOException e){
  			//buzz
  		}*/
  	}

  	//機器情報取得
  	public void getSensorData(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x10;
  		sendData[2] = (byte)0x00;
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//時刻設定（年(2000年からの経過年数)，月，日，時，分，秒，ミリ秒）
  	public void setTime(OutputStream out, int year, int month, int day, int hour, int min, int sec, int msec){
  		int commandLength = 11;
  		if(year < 0 || year > 99)	year = 0;
  		if(month < 1 || month > 12)	month = 1;
  		if(day < 1 || day > 31)		day = 1;
  		if(hour < 0 || hour > 23)	hour = 0;
  		if(min < 0 || min > 59)		min = 0;
  		if(sec < 0 || sec > 59)		sec = 0;
  		if(msec < 0 || msec > 999)	msec = 0;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x11;
  		sendData[2] = (byte)year;
  		sendData[3] = (byte)month;
  		sendData[4] = (byte)day;
  		sendData[5] = (byte)hour;
  		sendData[6] = (byte)min;
  		sendData[7] = (byte)sec;
  		sendData[8] = (byte)(msec%16);	//リトルエンディアン
  		sendData[9] = (byte)(msec/16);
  		sendData[10] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//時刻取得
  	public void getTime(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;		//Header (0x9a fixed) [1 byte]
  		sendData[1] = (byte)0x12;		//Command Code		  [1 byte]
  		sendData[2] = (byte)0x00;		//Parameter			  [1-264 byte]
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}
  //計測開始（計測時間[秒]）
  	public void startMeasure(OutputStream out){
  		int commandLength = 17;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;	//Header (0x9a fixed) [1 byte]
  		sendData[1] = (byte)0x13;	//Command Code		  [1 byte]
  		sendData[2] = (byte)0x00;	//相対(0) or 絶対時刻(1)
  		sendData[3] = (byte)0x0c;	//開始年
  		sendData[4] = (byte)0x0c;	//開始月
  		sendData[5] = (byte)0x01;	//開始日
  		sendData[6] = (byte)0x00;	//開始時
  		sendData[7] = (byte)0x00;	//開始分
  		sendData[8] = (byte)0x00;	//開始秒
  		sendData[9] = (byte)0x00;	//相対(0) or 絶対時刻(1)
  		sendData[10] = (byte)0x0c;	//終了年
  		sendData[11] = (byte)0x0c;	//終了月
  		sendData[12] = (byte)0x01;	//終了日
  		sendData[13] = (byte)0x00;	//終了時
  		sendData[14] = (byte)0x00;	//終了分
  		sendData[15] = (byte)0x00;	//終了秒
  		sendData[16] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);	//BCC
  		}
  		sendDatum(out, sendData);
  	}
  	//計測開始（計測時間[秒]）
  	public void startMeasure(OutputStream out, int sec){
  		int commandLength = 17;
  		byte[] sendData = new byte[commandLength];
  		if(sec < 0 || sec > 255)sec = 0;
  		sendData[0] = (byte)0x9a;	//Header (0x9a fixed) [1 byte]
  		sendData[1] = (byte)0x13;	//Command Code		  [1 byte]
  		sendData[2] = (byte)0x00;	//相対(0) or 絶対時刻(1)
  		sendData[3] = (byte)0x0c;	//開始年
  		sendData[4] = (byte)0x0c;	//開始月
  		sendData[5] = (byte)0x01;	//開始日
  		sendData[6] = (byte)0x00;	//開始時
  		sendData[7] = (byte)0x00;	//開始分
  		sendData[8] = (byte)0x00;	//開始秒
  		sendData[9] = (byte)0x00;	//相対(0) or 絶対時刻(1)
  		sendData[10] = (byte)0x0c;	//終了年
  		sendData[11] = (byte)0x0c;	//終了月
  		sendData[12] = (byte)0x01;	//終了日
  		sendData[13] = (byte)0x00;	//終了時
  		sendData[14] = (byte)0x00;	//終了分
  		sendData[15] = (byte)sec;	//終了秒
  		sendData[16] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);	//BCC
  		}
  		sendDatum(out, sendData);
  	}
  	//計測開始（計測時間[秒]）
  	public void startMeasure(OutputStream out,
  			int sMode, int sYear, int sMon, int sDay, int sHour, int sMin, int sSec, 
  			int eMode, int eYear, int eMon, int eDay, int eHour, int eMin, int eSec
  			){
  		//データチェック
  		if(sYear < 0 || sYear > 99)	sYear = 0;
  		if(sMon < 1 || sMon > 12)	sMon = 1;
  		if(sDay < 1 || sDay > 31)	sDay = 1;
  		if(sHour < 0 || sHour > 23)	sHour = 0;
  		if(sMin < 0 || sMin > 59)	sMin = 0;
  		if(sSec < 0 || sSec > 59)	sSec = 0;
  		if(eYear < 0 || eYear > 99)	eYear = 0;
  		if(eMon < 1 || eMon > 12)	eMon = 1;
  		if(eDay < 1 || eDay > 31)	eDay = 1;
  		if(eHour < 0 || eHour > 23)	eHour = 0;
  		if(eMin < 0 || eMin > 59)	eMin = 0;
  		if(eSec < 0 || eSec > 59)	eSec = 0;
//  		System.out.println("invalid input!");
  		//日付の前後関係がおかしい時のエラー表示
//  		System.out.println("invalid input!");			

  		int commandLength = 17;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;	//Header (0x9a fixed) [1 byte]
  		sendData[1] = (byte)0x13;	//Command Code		  [1 byte]
  		sendData[2] = (byte)sMode;	//相対(0) or 絶対時刻(1)
  		sendData[3] = (byte)sYear;	//開始年
  		sendData[4] = (byte)sMon;	//開始月
  		sendData[5] = (byte)sDay;	//開始日
  		sendData[6] = (byte)sHour;	//開始時
  		sendData[7] = (byte)sMin;	//開始分
  		sendData[8] = (byte)sSec;	//開始秒
  		sendData[9] = (byte)eMode;	//相対(0) or 絶対時刻(1)
  		sendData[10] = (byte)eYear;	//終了年
  		sendData[11] = (byte)eMon;	//終了月
  		sendData[12] = (byte)eDay;	//終了日
  		sendData[13] = (byte)eHour;	//終了時
  		sendData[14] = (byte)eMin;	//終了分
  		sendData[15] = (byte)eSec;	//終了秒
  		sendData[16] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);	//BCC
  		}
  		sendDatum(out, sendData);
  	}

  	//計測予約確認
  	public void checkReserve(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x14;
  		sendData[2] = (byte)0x00;
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//計測停止
  	public void stopMeasure(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;		//Header (0x9a fixed) [1 byte]
  		sendData[1] = (byte)0x15;		//Command Code		  [1 byte]
  		sendData[2] = (byte)0x00;		//Parameter			  [1-264 byte]
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}	

  	//加速度・角速度計測設定（計測周期[ms]，データ平均回数[回]）
  	public void setAccelMeasurement(OutputStream out, int cycle, int ave, int recordAve){
  		if(cycle < 0 || cycle > 255)
  			cycle = 20;
  		if(ave < 0 || ave > 255)
  			ave = 10;
  		if(recordAve < 0 || recordAve > 255)
  			recordAve = 10;

  		int commandLength = 6;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;		//Header (0x9a fixed)		[1 byte]
  		sendData[1] = (byte)0x16;		//Command Code				[1 byte]
  		sendData[2] = (byte)cycle;		//計測周期 					[1-255ms]
  		sendData[3] = (byte)ave;		//計測データ送信設定・平均回数 	[1-255回]
  		sendData[4] = (byte)recordAve;	//データ記録時平均回数設定 		[0:しない, 1-255:する[回]]
  		sendData[5] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//加速度・角速度計測設定取得
  	public void getAccelMeasurement(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x17;
  		sendData[2] = (byte)0x00;
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//地磁気計測設定（計測周期[ミリ秒]，データ平均回数[回]）
  	public void setGeomagnetismMeasurement(OutputStream out, int cycle, int ave, int recordAve){
  		if(cycle < 0 || cycle > 255)			cycle = 20;
  		if(ave < 0 || ave > 255)				ave = 10;
  		if(recordAve < 0 || recordAve > 255)	recordAve = 10;

  		int commandLength = 6;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;		//Header (0x9a fixed) 		[1 byte]
  		sendData[1] = (byte)0x18;		//Command Code		  		[1 byte]
  		sendData[2] = (byte)cycle;		//計測周期 					[0-255ms]
  		sendData[3] = (byte)ave;		//計測データ送信設定・平均回数 	[1-255回]
  		sendData[4] = (byte)recordAve;	//データ記録設定 				[0:しない, 1-255:する[回]]
  		sendData[5] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  	}

  	//地磁気計測設定取得
  	public void getGeomagnetismMeasurement(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x19;
  		sendData[2] = (byte)0x00;
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//気圧計測設定（計測周期[ミリ秒]，データ平均回数[回]）
  	public void setPressureMeasurement(OutputStream out, int cycle, int ave, int recordAve){
  		if(cycle < 0 || cycle > 255)	cycle = 20;
  		if(ave < 4 || ave > 255)		ave = 10;
  		if(recordAve < 0 || recordAve > 255)	recordAve = 10;

  		int commandLength = 6;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;		//Header (0x9a fixed) 		[1 byte]
  		sendData[1] = (byte)0x1a;		//Command Code		  		[1 byte]
  		sendData[2] = (byte)cycle;		//計測周期÷10 				[40-2550ms]
  		sendData[3] = (byte)ave;		//計測データ送信設定・平均回数 	[1-255回]
  		sendData[4] = (byte)recordAve;	//データ記録設定 				[0:しない]
  		sendData[5] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  	}

  	//気圧計測設定取得
  	public void getPressureMeasurement(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x1b;
  		sendData[2] = (byte)0x00;
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//バッテリ電圧計測設定
  	public void setVoltageMeasurement(OutputStream out, boolean send, boolean record){
  		int commandLength = 5;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x1c;
  		if(send == false){
  			sendData[2] = (byte)0x00;	//送信しない
  		}else{
  			sendData[2] = (byte)0x01;	//送信する
  		}
  		if(record == false){
  			sendData[3] = (byte)0x00;	//記録しない
  		}else{
  			sendData[3] = (byte)0x01;	//記録する
  		}
  		sendData[4] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//バッテリ電圧計測設定取得
  	public void getVoltageMeasurement(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x1d;
  		sendData[2] = (byte)0x00;
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//外部拡張端子計測＆エッジデータ出力設定
  	public void setExternIOMeasurement(OutputStream out, int cycle, int ave, int recordAve, boolean send, boolean record){
  		if(cycle < 0 || cycle > 255)	cycle = 20;
  		if(ave < 4 || ave > 255)		ave = 10;
  		if(recordAve < 0 || recordAve > 255)	recordAve = 10;

  		int commandLength = 8;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;		//Header (0x9a fixed) 		[1 byte]
  		sendData[1] = (byte)0x1e;		//Command Code		  		[1 byte]
  		sendData[2] = (byte)cycle;		//計測周期(2ms刻み) 			[0-254ms]
  		sendData[3] = (byte)ave;		//計測データ送信設定・平均回数 	[1-255回]
  		sendData[4] = (byte)recordAve;	//データ記録設定 				[0-255ms]
  		if(send == false){
  			sendData[5] = (byte)0x00;	//送信しない
  		}else{
  			sendData[5] = (byte)0x01;	//送信する
  		}
  		if(record == false){
  			sendData[6] = (byte)0x00;	//記録しない
  		}else{
  			sendData[6] = (byte)0x01;	//記録する
  		}
  		sendData[7] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  	}

  	//外部拡張端子計測＆エッジデータ出力設定取得
  	public void getExternIOMeasurement(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x1f;
  		sendData[2] = (byte)0x00;
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//外部拡張I2C通信設定
  	public void setExternI2CCommunication(OutputStream out, int cycle, boolean send, boolean record){
  		if(cycle < 0 || cycle > 255)	cycle = 20;

  		int commandLength = 6;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;		//Header (0x9a fixed) 		[1 byte]
  		sendData[1] = (byte)0x20;		//Command Code		  		[1 byte]
  		sendData[2] = (byte)cycle;		//計測周期(2ms刻み) 			[0-254ms]
  		if(send == false){
  			sendData[3] = (byte)0x00;	//送信しない
  		}else{
  			sendData[3] = (byte)0x01;	//送信する
  		}
  		if(record == false){
  			sendData[4] = (byte)0x00;	//記録しない
  		}else{
  			sendData[4] = (byte)0x01;	//記録する
  		}
  		sendData[5] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  	}

  	//外部拡張I2C通信取得
  	public void getExternI2CCommunication(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x21;
  		sendData[2] = (byte)0x00;
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//加速度センサ計測レンジ設定
  	public void setAccelRange(OutputStream out, int range){
  		if(range < 0 || range > 3)	range = 0;

  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x22;
  		sendData[2] = (byte)range;		//0:±2G, 1:±4G, 2:±8G, 3:±16G
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

  	//加速度センサ計測レンジ設定取得
  	public void getAccelRange(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x23;
  		sendData[2] = (byte)0x00;
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}
  	
  	//binの実装
  	public void bin(OutputStream out , String[]bin){
  		int commandLength = bin.length+1;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		for(int i = 1; i < commandLength -2; i++){
  			sendData[i] = (byte)Integer.parseInt(bin[i], 16);
  		}
  		sendData[commandLength-1] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}
  //バッテリ電圧計測設定取得
  	public void getVoltage(OutputStream out){
  		int commandLength = 4;
  		byte[] sendData = new byte[commandLength];
  		sendData[0] = (byte)0x9a;
  		sendData[1] = (byte)0x3B;
  		sendData[2] = (byte)0x00;
  		sendData[3] = (byte)0x00;
  		for(int i = 0; i < commandLength-1; i++){
  			sendData[commandLength-1] = (byte)(sendData[commandLength-1] ^ sendData[i]);
  		}
  		sendDatum(out, sendData);
  	}

	public void write(String message) throws IOException {
		// TODO 自動生成されたメソッド・スタブ
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(message);	
	}

}


