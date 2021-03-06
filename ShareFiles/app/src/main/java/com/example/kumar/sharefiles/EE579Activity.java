package com.example.kumar.sharefiles;


import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.ChannelListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

import static android.content.ContentValues.TAG;
import static com.example.kumar.sharefiles.R.*;

public class EE579Activity extends Activity implements ChannelListener {
    public final static int BYTESPERCHUNK = 100000;
    /* The following part is a Hash Table + BitMap solution.
     * A hash table maintain the info of all available files and each file maintain its chunk list using a bitmap.
     * */
    public static HashMap<String, BitMap> availableChunkMap = new HashMap<String, BitMap>();
    public static HashMap<String, BitMap> neededChunkMap = new HashMap<String, BitMap>();
    static HashMap<String, String> allFileList = new HashMap<String, String>();
    static HashMap<String, Integer> numOfChunks = new HashMap<String, Integer>();
    static String fileNeeded = new String();
    private final IntentFilter intentFilter = new IntentFilter();
    Context CONTEXT = this;
    /**
     * Called when the activity is first created.
     */

    private WifiP2pManager manager;
    private Channel channel;
    private BroadcastReceiver receiver;
    private boolean isWifiP2pEnabled = false;
    private boolean retryChannel = false;

    //map the status code with words
    public static String getDeviceStatus(int deviceStatus) {
        switch (deviceStatus) {
            case WifiP2pDevice.AVAILABLE:
                return "Available";
            case WifiP2pDevice.INVITED:
                return "Invited";
            case WifiP2pDevice.CONNECTED:
                return "Connected";
            case WifiP2pDevice.FAILED:
                return "Failed";
            case WifiP2pDevice.UNAVAILABLE:
                return "Unavailable";
            default:
                return "Unknown";

        }
    }

    // Round up division result
    public static int divRoundUp(int n, int s) {
        return (((n) / (s)) + ((((n) % (s)) > 0) ? 1 : 0));
    }

    // get the file needed in string from the need chunk map. in the form of "file number, bitmap in string, .."
    public static String getFileNeeded() {
        String result = new String();
        Set<String> files = neededChunkMap.keySet();
        if (files.isEmpty()) return null;
        Iterator<String> it = files.iterator();
        int i = 0;
        while (it.hasNext()) {
            if (i++ != 0) result += ",";
            String buffer = it.next();
            result += buffer + ",";
            result += neededChunkMap.get(buffer).toString();
        }
        return result;
    }

    // update the record file with the current available chunk map. the map is updated whenever a new chunk is received
    // so this will keep the record file with latest info about which chunks are available
    public static void updateRecord() {
        File recordFile = new File("/sdcard/ee579/recordFile.txt");
        try {
            BufferedWriter outputWriter = new BufferedWriter(new FileWriter(recordFile, false));
            Set<String> files = availableChunkMap.keySet();
            Iterator<String> it = files.iterator();
            while (it.hasNext()) {
                String buffer = it.next();
                // write out the file number+ file name, and the bitmap of that file as string.
                outputWriter.write(buffer + "," + allFileList.get(buffer) + "\n");
                BitMap chunkMap = availableChunkMap.get(buffer);
                outputWriter.write(chunkMap.toString() + "\n");
            }
            outputWriter.flush();
            outputWriter.close();
        } catch (IOException e) {
            Log.d("EE579", "IO Error.");
        }
    }

    public  boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                Log.v(TAG,"Permission is granted");
                return true;
            } else {

                Log.v(TAG,"Permission is revoked");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                return false;
            }
        }
        else { //permission is automatically granted on sdk<23 upon installation
            Log.v(TAG,"Permission is granted");
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(grantResults[0]== PackageManager.PERMISSION_GRANTED){
            Log.v(TAG,"Permission: "+permissions[0]+ "was "+grantResults[0]);
            //resume tasks needing this permission
        }
    }
    //private WifiP2pDevice device;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(layout.main);
        // create the app folder if doesn't exit and do the initialization work.
        isStoragePermissionGranted();
        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(builder.build());
        File file = new File("/sdcard/ee579/");
        showMessage(String.valueOf(file));
        if (!file.exists()) file.mkdirs();
        initialization();
        fileNeeded = getFileNeeded();
        //register for the events we want to capture
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        //create necessary manager and channel
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);
        registerReceiver(receiver, intentFilter);
        //searchPeer();
    }

    /**
     * register the BroadcastReceiver with the intent values to be matched
     */
    @Override
    public void onResume() { // register the receiver on resume
        super.onResume();
        registerReceiver(receiver, intentFilter);
    }

    @Override
    public void onPause() {  // unregister on pause
        super.onPause();
        unregisterReceiver(receiver);
    }

    //create option menu. a directory browser and a close
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(layout.menuitem, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case id.exit:            // close button. do the clean up work
                disconnect();
                cancelDisconnect();
                unregisterReceiver(receiver);
                this.finish();
                System.exit(0);
                return true;
            case id.folder:            // directory browser
                Intent browseFolder = new Intent(this, BrowserFolder.class);
                startActivity(browseFolder);    // open directory browser in a new activity
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    //indicate the wifi direct state
    public void setIsWifiP2pEnabled(boolean isWifiP2pEnabled) {
        this.isWifiP2pEnabled = isWifiP2pEnabled;
        return;
    }

    //to use a toast to show message on screen
    public void showMessage(String str) {
        Toast.makeText(CONTEXT, str, Toast.LENGTH_SHORT).show();
    }

    //function to be called when search button is clicked.
    public void searchButton(View view) {
        searchPeer();
        return;
    }

    //function to perform the wifi direct search
    public void searchPeer() {
        //check if wifi direct is enabled
        if (!isWifiP2pEnabled) {
            new AlertDialog.Builder(this)
                    .setIcon(mipmap.ic_launcher)
                    .setTitle("WiFi Direct is Disabled!")
                    .setPositiveButton("Setting", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                        }
                    }).show();
            return;
        }
        if (fileNeeded == null) {
            showMessage("Updated Peers.");
            return;
        }
/*        DeviceDetailFragment devicefragment = (DeviceDetailFragment)getFragmentManager().findFragmentById(R.id.devicedetail);
    if(devicefragment.device!=null&&devicefragment.isConnected){
	    showMessage("Please disconnect the current connection first.");
	    return;
	}*/
        //use fragment class to display all devices
        final DeviceListFragment fragment = (DeviceListFragment) getFragmentManager()
                .findFragmentById(id.devicelist);
        fragment.onInitiateDiscovery();
        fragment.getView().setVisibility(View.VISIBLE);
        // use dicoverpeers to find peers. to get the peer list, call request peers on success
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                //Toast.makeText(CONTEXT, "Searching",Toast.LENGTH_SHORT).show();
                return;
            }

            @Override
            public void onFailure(int reasonCode) {
                Toast.makeText(CONTEXT, "Search Failed: " + reasonCode, Toast.LENGTH_SHORT).show();
                return;
            }
        });
    }

    //show device info on screen
    public void updateThisDevice(WifiP2pDevice device) {
        TextView view = (TextView) findViewById(id.mystatus);
        view.setText("My Name: " + device.deviceName + "\nMy Address: " + device.deviceAddress + "\nMy Status: " + getDeviceStatus(device.status));
        return;
    }

    //wifi direct connect function
    public void connect(WifiP2pConfig config) {
        manager.connect(channel, config, new ActionListener() {
            @Override
            public void onSuccess() {
                // WiFiDirectBroadcastReceiver will notify us. Ignore for now.
            }

            @Override
            public void onFailure(int reason) {
                showMessage("Connect failed: " + reason);
            }
        });
        return;
    }

    //wifi direct disconnect function
    public void disconnect() {
        final DeviceDetailFragment fragment = (DeviceDetailFragment) getFragmentManager()
                .findFragmentById(id.devicedetail);
        fragment.blockDetail();
        updateRecord();
        manager.removeGroup(channel, new ActionListener() {
            @Override
            public void onFailure(int reasonCode) {
                showMessage("Disconnect failed. Reason :" + reasonCode);
            }

            @Override
            public void onSuccess() {
                //fragment.getView().setVisibility(View.GONE);
                showMessage("Disconnected.");
            }
        });
        return;
    }

    // prevent channel lose. if lost, try to create it again.
    @Override
    public void onChannelDisconnected() {
        if (manager != null && !retryChannel) {
            Toast.makeText(this, "Channel lost. Trying again", Toast.LENGTH_LONG).show();
            retryChannel = true;
            manager.initialize(this, getMainLooper(), this);
        } else {
            Toast.makeText(this,
                    "Channel is probably lost premanently. Try Disable/Re-Enable P2P.",
                    Toast.LENGTH_LONG).show();
        }
        return;
    }

    //cancel ongoing connect action
    public void cancelDisconnect() {
        if (manager != null) {
            final DeviceDetailFragment fragment = (DeviceDetailFragment) getFragmentManager()
                    .findFragmentById(id.devicedetail);
            if (fragment.device == null
                    || fragment.device.status == WifiP2pDevice.CONNECTED) {
                disconnect();
            } else if (fragment.device.status == WifiP2pDevice.AVAILABLE
                    || fragment.device.status == WifiP2pDevice.INVITED) {

                manager.cancelConnect(channel, new ActionListener() {

                    @Override
                    public void onSuccess() {
                        showMessage("Aborting connection");
                        return;
                    }

                    @Override
                    public void onFailure(int reasonCode) {
                        showMessage("Connect abort request failed. Reason Code: " + reasonCode);
                    }
                });
            }
        }
        return;
    }

    void initialization() {
        String a = String.valueOf("/sdcard/ee579/Masterfile.txt");
        File listFile = new File("/sdcard/ee579/Masterfile.txt");
        File recordFile = new File("/sdcard/ee579/recordFile.txt");
        try {
            if (!listFile.exists()) {    // need to have a config file list all files available
                showMessage("Fatal Error: Config file not found.");
                return;
            }
            BufferedReader inputReader = new BufferedReader(new FileReader(listFile));
            String buffer = new String();
            // read from the config file to get the file info for all the possible files.
            // store them in a hashtable with number-filename pairs and number-number of chunks pair.
            while ((buffer = inputReader.readLine()) != null) {
                String[] fileInfo = buffer.split(",");
                allFileList.put(fileInfo[0], fileInfo[1]);
                int num = divRoundUp(Integer.parseInt(fileInfo[2]), BYTESPERCHUNK);
                numOfChunks.put(fileInfo[0], num);
                // if the files on the list exist on the phone. record them on a hashtable-bitmap.
                // can check this to see if it has the file or not when there are requests
                File oneFile = new File("/sdcard/ee579/" + fileInfo[1]);
                if (oneFile.exists()) {
                    BitMap chunkMap = new BitMap(num);
                    for (int i = 0; i < num; i++) chunkMap.Mark(i);
                    availableChunkMap.put(fileInfo[0], chunkMap);
                }
            }
            inputReader.close();
            if(recordFile.exists()){
                recordFile.renameTo(new File("oldRecordFile.txt"));
            }
            recordFile.createNewFile();
            // read records from the record file. if there is no record file, create one.
            // the records file keep records about available chunks. update the file whenever received a new chunk.
            inputReader = new BufferedReader(new FileReader(recordFile));
            while ((buffer = inputReader.readLine()) != null) {
                String[] fileInfo = buffer.split(",");
                if (availableChunkMap.get(fileInfo[0]) != null) {
                    // if we have already had the record about this file skip it.
                    buffer = inputReader.readLine();
                    continue;
                }
                if ((buffer = inputReader.readLine()) != null) {
                    // the record file keep the record as "file number+chunk bitmap record"
                    // so read the bitmap as string and create a bitmap and store it in the hashtable
                    BitMap chunkMap = new BitMap(buffer);
                    if (chunkMap.length() != numOfChunks.get(fileInfo[0])) {
                        showMessage("Error: BitMap length not correct");
                        return;
                    }
                    availableChunkMap.put(fileInfo[0], chunkMap);
                }
            }
            inputReader.close();
            // calculate all files needed according to all possible files and all available files
            Set<String> files = allFileList.keySet();
            Iterator<String> it = files.iterator();
            while (it.hasNext()) {
                buffer = it.next();
                if (availableChunkMap.get(buffer) == null) {
                    // if the file number is not in the available file record, the whole file is missing
                    // create a bitmap and clear all bits and store it in the need map
                    BitMap chunkMap = new BitMap(numOfChunks.get(buffer));
                    for (int i = 0; i < numOfChunks.get(buffer); i++) {
                        chunkMap.Mark(i);
                    }
                    neededChunkMap.put(buffer, chunkMap);
                } else {
                    // if the file number is in the record, then the whole file or at least some chunks are available
                    // create a bitmap, check which chunk is unavailable, and mark the bit.
                    BitMap chunkMap = availableChunkMap.get(buffer);
                    if (chunkMap.numMarked() == numOfChunks.get(buffer)) continue;
                    BitMap neededChunk = new BitMap(numOfChunks.get(buffer));
                    for (int i = 0; i < numOfChunks.get(buffer); i++) {
                        if (!chunkMap.Test(i)) {
                            neededChunk.Mark(i);
                        }
                    }
                    neededChunkMap.put(buffer, neededChunk);
                }
            }
        } catch (IOException e) {
            showMessage("IO Error: " + e.toString());
        }
    }
}