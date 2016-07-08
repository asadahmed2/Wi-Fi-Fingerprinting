package emc.zebra.com.fingerprinting;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements SensorEventListener, StepListener{

    private static final int STEP_COUNT_THRESHOLD = 2; // 2 steps
    private static final int WIFI_CONTINUOUS_SCAN_COUNT = 10;
    private static final int MANUAL_SCAN_DELAY = 2 * 1000; // 2 seconds
    private static final int TOP_RECORDS_FROM_SORTED_RSSI_LIST = 4;

    // Fingerprint file names
    private static final String CONTINUOUS_SCAN_FILE = "wifi_trace_continuous_scan.txt";
    private static final String MANUAL_SCAN_FILE = "wifi_trace_manual_scan_with_delay.txt";
    private static final String STEP_DETECTION_SCAN_FILE = "wifi_trace_step_detection.txt";

    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private WifiManager mWifiManager;
    private WifiReceiver mWifiReceiver;
    private List<ScanResult> mWifiList;
    private int count = 0, stepCount = 1;;
    private float x, y, z;
    private String fingerprintFilePath = "", apName = null;
    private StepDetector mStepDetector = null;
    private boolean toggleConfigure = true, isContinuousScanning = false, isManualStartWithTimer = false,
            isStepDetection = false, stillCapturing = false;
    private HashMap<String, ArrayList<Integer>> map = new HashMap<String, ArrayList<Integer>>();

    // Views
    TextView tvStatus, tvMain;
    LinearLayout llConfigure;
    EditText etConfigure;
    Button btnConfigure, btnManualStartWithTimer, btnContinuousScanning;

    IntentFilter mIntentFilter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);

    // Handlers
    Handler mManualScanHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            showStatus(MANUAL_SCAN_DELAY + " delay - end");
            registerReceiver(mWifiReceiver, mIntentFilter);
            mWifiManager.startScan();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (!mWifiManager.isWifiEnabled()) {
            Toast.makeText(this, "wifi is disabled..making it enabled", Toast.LENGTH_LONG).show();
            mWifiManager.setWifiEnabled(true);
        }
        mWifiReceiver = new WifiReceiver();
        mIntentFilter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);

        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvStatus.setMovementMethod(new ScrollingMovementMethod());
        tvMain = (TextView) findViewById(R.id.tv_main);
        llConfigure = (LinearLayout) findViewById(R.id.ll_configure);
        etConfigure = (EditText) findViewById(R.id.et_configure);
        btnConfigure = (Button) findViewById(R.id.btn_configure);
        btnManualStartWithTimer = (Button) findViewById(R.id.btn_manual_start_with_timer);
        btnContinuousScanning = (Button) findViewById(R.id.btn_continuous_scanning);
        btnConfigure.setOnClickListener(onClickListener);
        btnManualStartWithTimer.setOnClickListener(onClickListener);
        btnContinuousScanning.setOnClickListener(onClickListener);
        mStepDetector = new StepDetector(this, this);
        showStatus("Enter Access point name using configure option in menu");
    }

    public void onPause() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        // mStepDetector.unregisterDetector();
        // mSensorManager.unregisterListener(this);
        // unregisterReceiver(mWifiReceiver);
        super.onPause();
    }

    public void onResume() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // mWifiManager.startScan();
        // mStepDetector.registerDetector();
        super.onResume();
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.action_bar_menu, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_configure:
                if(toggleConfigure) {
                    llConfigure.setVisibility(View.VISIBLE);
                } else {
                    llConfigure.setVisibility(View.GONE);
                }
                toggleConfigure = !toggleConfigure;
                updateScanningFlags(false, false, false);
                break;
            case R.id.action_step_detection:
                if(null == apName) {
                    Toast.makeText(getApplicationContext(), "Enter the Access point name first, using configure option in menu", Toast.LENGTH_LONG).show();
                    return true;
                }
                String msg2 = "Started Wi-Fi Scanning on Step Detection";
                if(isStepDetection) {
                    msg2 = "Stopping Wi-Fi Scanning on Step Detection";
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this);
                    dialog.setTitle("Alert").setMessage("Already in Step Detection mode. Do you want to stop it");
                    dialog.setPositiveButton("Yes", new AlertDialog.OnClickListener(){
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            count = 0;
                            map.clear();
                            unregisterReceiver(mWifiReceiver);
                            mSensorManager.unregisterListener(MainActivity.this);
                            updateScanningFlags(false, false, false);
                            Toast.makeText(getApplicationContext(), "Stopped Wi-Fi Scanning on Step Detection", Toast.LENGTH_SHORT).show();
                        }
                    });
                    dialog.setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
                    dialog.show();
                } else {
                    updateScanningFlags(false, false, true);
                    registerReceiver(mWifiReceiver, mIntentFilter);
                    mSensorManager.registerListener(MainActivity.this, mAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
                    mWifiManager.startScan();
                    Toast.makeText(getApplicationContext(), msg2, Toast.LENGTH_SHORT).show();
                }
                break;
            case R.id.action_continuous_scanning:
                if(null == apName) {
                    Toast.makeText(getApplicationContext(), "Enter the Access point name first, using configure option in menu", Toast.LENGTH_LONG).show();
                    return true;
                }
                String msg = "Started Continuous Scanning";
                if(isContinuousScanning) {
                    msg = "Stopping Continuous Scanning";
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this);
                    dialog.setTitle("Alert").setMessage("Already in continuous scanning mode. Do you want to stop it");
                    dialog.setPositiveButton("Yes", new AlertDialog.OnClickListener(){
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            count = 0;
                            map.clear();
                            unregisterReceiver(mWifiReceiver);
                            updateScanningFlags(false, false, false);
                            Toast.makeText(getApplicationContext(), "Stopped continuous scanning", Toast.LENGTH_SHORT).show();
                        }
                    });
                    dialog.setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
                    dialog.show();
                } else {
                    updateScanningFlags(true, false, false);
                    registerReceiver(mWifiReceiver, mIntentFilter);
                    mWifiManager.startScan();
                    Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                }
                break;
            case R.id.action_manual_start_with_timer:
                if(null == apName) {
                    Toast.makeText(getApplicationContext(), "Enter the Access point name first, using configure option in menu", Toast.LENGTH_LONG).show();
                    return true;
                }
                String message = "Starting manual scanning with 2s delay";
                if(isManualStartWithTimer) {
                    Toast.makeText(getApplicationContext(), "Already in Manual start mode", Toast.LENGTH_SHORT).show();
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this);
                    dialog.setTitle("Alert").setMessage("Already in Manual start mode. Do you want to stop it");
                    dialog.setPositiveButton("Yes", new AlertDialog.OnClickListener(){
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            count = 0;
                            map.clear();
                            unregisterReceiver(mWifiReceiver);
                            updateScanningFlags(false, false, false);
                            Toast.makeText(getApplicationContext(), "Stopped manual scanning", Toast.LENGTH_SHORT).show();
                        }
                    });
                    dialog.setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
                    dialog.show();
                } else {
                    updateScanningFlags(false, true, false);
                    showStatus(MANUAL_SCAN_DELAY + " delay - start");
                    mManualScanHandler.sendEmptyMessageDelayed(1, MANUAL_SCAN_DELAY);
                    Toast.makeText(getApplicationContext(), "Started manual scanning", Toast.LENGTH_SHORT).show();
                }
                break;
            case R.id.action_clear_continuous_scan_data:
                deleteFingerprintFile(CONTINUOUS_SCAN_FILE);
                break;
            case R.id.action_clear_manual_scan_data:
                deleteFingerprintFile(MANUAL_SCAN_FILE);
                break;
            case R.id.action_clear_step_detection_scan_data:
                deleteFingerprintFile(STEP_DETECTION_SCAN_FILE);
                break;
        }
        return true;
    }

    View.OnClickListener onClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.btn_configure:
                    apName = etConfigure.getText().toString();
                    etConfigure.setText("");
                    llConfigure.setVisibility(View.GONE);
                    toggleConfigure = !toggleConfigure;
                    showStatus("Collecting fingerprints");
                    registerReceiver(mWifiReceiver, mIntentFilter);
                    break;
                default:
                    break;
            }
        }
    };

    class WifiReceiver extends BroadcastReceiver {
        // This method call when number of wifi connections changed
        public void onReceive(Context c, Intent intent) {
            processNormalScanningResults();
        }
    }

    private void processNormalScanningResults() {
        if (null == apName)
            return;
        int state = mWifiManager.getWifiState();
        int maxLevel = 5;
        mWifiList = mWifiManager.getScanResults();
        int size = mWifiList.size();
        showStatus("Wi-Fi scanning results");
        showStatus("results size: " + size);
        if (size > 0) {
            int i = 0, signalList[] = new int[3];
            String[] macList = new String[3], bssid = new String[3];
            for (ScanResult result : mWifiList) {
                // The level of each wifiNetwork from 0-5
                // int level = WifiManager.calculateSignalLevel(result.level,maxLevel);
                String ssid = result.SSID;
                if(apName.equals(ssid)) {
                    if(i == 3)
                        break;
                    signalList[i] = result.level;
                    macList[i] = ssid;
                    bssid[i++] = result.BSSID;
                }
            }
            int s1 = signalList[0] == 0? -200:signalList[0],
                    s2 = signalList[1] == 0? -200:signalList[1],
                    s3 = signalList[2] == 0? -200:signalList[2];
            String m1 = macList[0] == null? "NA":macList[0],
                    m2 = macList[1] == null? "NA":macList[1],
                    m3 = macList[2] == null? "NA":macList[2];
            String b1 = bssid[0] == null? "NA":bssid[0],
                    b2 = bssid[1] == null? "NA":bssid[1],
                    b3 = bssid[2] == null? "NA":bssid[2];
            for(int k = 0; k < 3; k++) {
                String key = (macList[k] == null? "NA":macList[k]) + "," + (bssid[k] == null? "NA":bssid[k]);
                ArrayList<Integer> list = map.get(key);
                if(null == list) {
                    list = new ArrayList<Integer>();
                    map.put(key, list);
                }
                list.add(signalList[k] == 0? -200:signalList[k]);
            }
            count++;

            // exception after 12 continuous scans, Below code is the work around
            if (count < WIFI_CONTINUOUS_SCAN_COUNT) {
                stillCapturing = true;
                showStatus("Scanning again");
                mWifiManager.startScan();
            } else {
                // unregisterReceiver(mWifiReceiver); //stops the continuous scan
                // registerReceiver(mWifiReceiver, mIntentFilter);
                ArrayList<Integer> rssiList;
                String mac;
                StringBuffer sb = new StringBuffer();
                for(Map.Entry<String, ArrayList<Integer>> entry: map.entrySet()) {
                    mac = entry.getKey();
                    rssiList = entry.getValue();
                    int length = rssiList.size();
                    if(length == 0)
                        continue;
                    Collections.sort(rssiList);
                    int avg = 0, sum = 0, j = length - 1, c = 0;
                    for(; (j >= 0 && j >= length - TOP_RECORDS_FROM_SORTED_RSSI_LIST); j--) {
                        sum += rssiList.get(j);
                        ++c;
                    }
                    avg = sum/c;
                    sb.append("," + mac + "," + avg);
                }
                map.clear();
                count = 0;
                if(isContinuousScanning) {
                    writeFile(x + "," + y + "," + z + sb.toString(), true, CONTINUOUS_SCAN_FILE);
                    registerReceiver(mWifiReceiver, mIntentFilter);
                    mWifiManager.startScan();
                } else if(isManualStartWithTimer) {
                    isManualStartWithTimer = false;
                    writeFile(x + "," + y + "," + z + sb.toString(), true, MANUAL_SCAN_FILE);
                    stillCapturing = false;
                    unregisterReceiver(mWifiReceiver);
                    registerReceiver(mWifiReceiver, mIntentFilter);
                } else {
                    writeFile(x + "," + y + "," + z + sb.toString(), true, STEP_DETECTION_SCAN_FILE);
                    // unregisterReceiver(mWifiReceiver);
                    stillCapturing = false;
                }
                showStatus("Fingerprints saved to " + fingerprintFilePath);
            }
        }
    }

    private void continuousScanningMode() {
        isContinuousScanning = true;
        isManualStartWithTimer = false;
        registerReceiver(mWifiReceiver, mIntentFilter);
    }

    @Override
    public void onStep() {
        showStatus("Step detected");
        if(stillCapturing)
            return;
        if(stepCount == STEP_COUNT_THRESHOLD) {
            mWifiManager.startScan();
            showStatus(STEP_COUNT_THRESHOLD + " Steps detected, Collecting fingerprints");
            showMainStatus(STEP_COUNT_THRESHOLD + " Steps detected, Collecting fingerprints");
            stepCount = 0;
        }
        ++stepCount;
    }

    private void showStatus(String status) {
        // tvStatus.append(status + "\n");
        tvStatus.setText(status + "\n" + tvStatus.getText());
    }

    private void showMainStatus(String status) {
        tvMain.setText(status);
    }

    public void writeFile(String mValue, boolean isWifi, String f) {
        try {
            File sdcard = Environment.getExternalStorageDirectory();
            File file = new File(sdcard, f);
            if(isWifi) {
                fingerprintFilePath = file.getAbsolutePath();
            }
            if (!file.exists()) {
                file.createNewFile();
            }
            FileWriter writer = new FileWriter(file, true);
            long timeInMillis = System.currentTimeMillis();
            writer.write(timeInMillis + "," + mValue + "\n");
            writer.flush();
            writer.close();
            if(isWifi) {
                showStatus("fingerprint: " + mValue);
                showStatus("fingerprint saved");
            }
            // readFile(isWifi);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void readFile(String f) {
        File sdcard = Environment.getExternalStorageDirectory();
        File file = new File(sdcard, f);
        StringBuilder text = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                text.append(line);
                text.append('\n');
            }
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void deleteFingerprintFile(String fileName) {
        try{
            File sdcard = Environment.getExternalStorageDirectory();
            File file = new File(sdcard, fileName);
            if(file.delete()){
                System.out.println(file.getName() + " is deleted!");
            }else{
                System.out.println("Delete operation is failed.");
            }
        }catch(Exception e){
            e.printStackTrace();
        }
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }

    public void onSensorChanged(SensorEvent event) {
        //Right in here is where you put code to read the current sensor values and
        //update any views you might have that are displaying the sensor information
        //You'd get accelerometer values like this:
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER)
            return;
        x = event.values[0];
        y = event.values[1];
        z = event.values[2];
        double total = Math.sqrt(x * x + y * y + z * z);
        writeFile(x + "," + y + "," + z, false, "accelerometer_trace.txt");
        mStepDetector.onSensorChanged(event);
    }

    private void updateScanningFlags(boolean isContinuousScanning, boolean isManualStartWithTimer, boolean isStepDetection) {
        this.isContinuousScanning = isContinuousScanning;
        this.isManualStartWithTimer = isManualStartWithTimer;
        this.isStepDetection = isStepDetection;
    }

}