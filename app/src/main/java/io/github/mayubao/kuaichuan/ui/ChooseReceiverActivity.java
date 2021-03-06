package io.github.mayubao.kuaichuan.ui;

import android.content.Intent;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.mayubao.kuaichuan.AppContext;
import io.github.mayubao.kuaichuan.Constant;
import io.github.mayubao.kuaichuan.R;
import io.github.mayubao.kuaichuan.common.BaseActivity;
import io.github.mayubao.kuaichuan.core.BaseTransfer;
import io.github.mayubao.kuaichuan.core.MyWifiManager;
import io.github.mayubao.kuaichuan.core.entity.FileInfo;
import io.github.mayubao.kuaichuan.core.iOS.TranslateWithIOS;
import io.github.mayubao.kuaichuan.core.utils.ToastUtils;
import io.github.mayubao.kuaichuan.ui.adapter.WifiScanResultAdapter;
import io.github.mayubao.kuaichuan.ui.view.RadarScanView;
import io.github.mayubao.kuaichuan.utils.ListUtils;
import io.github.mayubao.kuaichuan.utils.NavigatorUtils;
import io.github.mayubao.kuaichuan.utils.NetUtils;


/**
 * Created by mayubao on 2016/11/28.
 * Contact me 345269374@qq.com
 */
public class ChooseReceiverActivity extends BaseActivity implements View.OnClickListener{

    private static final String TAG = ChooseReceiverActivity.class.getSimpleName();

    /**
     * 其他UI
     */
    RadarScanView radarScanView;

    /**
     * 扫描结果
     */
    ListView lv_result;

    List<ScanResult> mScanResultList;
    WifiScanResultAdapter mWifiScanResultAdapter;


    /**
     * 与 文件发送方 通信的 线程
     */
    Runnable mUdpServerRuannable;


    public static final int MSG_TO_FILE_SENDER_UI = 0X88;   //消息：跳转到文件发送列表UI
    public static final int MSG_TO_SHOW_SCAN_RESULT = 0X99; //消息：更新扫描可连接Wifi网络的列表

    Handler mHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            if(msg.what == MSG_TO_FILE_SENDER_UI){
                ToastUtils.show(getContext(), "进入文件发送列表");
                NavigatorUtils.toFileSenderListUI(getContext());
                finishNormal();
            }else if(msg.what == MSG_TO_SHOW_SCAN_RESULT){
                getOrUpdateWifiScanResult();
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_TO_SHOW_SCAN_RESULT), 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_choose_receiver);
        findViewById();

        init();
    }

    void findViewById(){
        TextView back = (TextView) findViewById(R.id.tv_back);
        TextView iosText = (TextView) findViewById(R.id.translate_to_ios);
        back.setOnClickListener(this);
        iosText.setOnClickListener(this);

        /**
         * 其他UI
         */
        radarScanView = (RadarScanView) findViewById(R.id.radarView);

        /**
         * 扫描结果
         */
        lv_result = (ListView) findViewById(R.id.lv_result);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        closeSocket();

        //断开当前的Wifi网络
        MyWifiManager.getInstance(getContext()).disconnectCurrentNetwork();

        this.finish();
    }

    /**
     * 成功进入 文件发送列表UI 调用的finishNormal()
     */
    private void finishNormal(){
        closeSocket();
        this.finish();
    }

    /**
     * 初始化
     */
    private void init(){
        radarScanView.startScan();

        if(!MyWifiManager.getInstance(getContext()).isWifiEnable()) {//wifi未打开的情况
            MyWifiManager.getInstance(getContext()).openWifi();
        }


        getOrUpdateWifiScanResult();
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_TO_SHOW_SCAN_RESULT), 1000);
    }

    /**
     * 获取或者更新wifi扫描列表
     */
    private void getOrUpdateWifiScanResult(){
        MyWifiManager.getInstance(getContext()).startScan();
        mScanResultList = MyWifiManager.getInstance(getContext()).getScanResultList();
        mScanResultList = ListUtils.filterWithNoPassword(mScanResultList);

        if(mScanResultList != null){
            mWifiScanResultAdapter = new WifiScanResultAdapter(getContext(),mScanResultList);
            lv_result.setAdapter(mWifiScanResultAdapter);
            lv_result.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    //TODO 进入文件传输部分
                    ScanResult scanResult = mScanResultList.get(position);
                    Log.i(TAG, "###select the wifi info ======>>>" + scanResult.toString());

                    //1.连接网络
                    String ssid = Constant.DEFAULT_SSID;
                    ssid = scanResult.SSID;
                    MyWifiManager.getInstance(getContext()).openWifi();
                    boolean isConnected = false;
                    int connectCount = 10;
                    while (!isConnected && connectCount > 0){
                        isConnected = MyWifiManager.getInstance(getContext()).addNetwork(MyWifiManager.createWifiCfg(ssid, null, MyWifiManager.WIFICIPHER_NOPASS));
                        --connectCount;
                    }
                    Log.i(TAG, "on connected");

                    //2.发送UDP通知信息到 文件接收方 开启ServerSocketRunnable
                    mUdpServerRuannable = createSendMsgToServerRunnable(MyWifiManager.getInstance(getContext()).getIpAddressFromHotspot());
                    AppContext.MAIN_EXECUTOR.execute(mUdpServerRuannable);
                }
            });
        }
    }

    @Override
    public void onClick(View v) {
        int viewId = v.getId();
        if (viewId == R.id.tv_back){
            onBackPressed();
        }
        if (viewId == R.id.translate_to_ios){
            Bundle bundle = new Bundle();
            bundle.putBoolean("isSend", true);
            Intent intent = new Intent(this, TranslateWithIOS.class);
            intent.putExtras(bundle);
            startActivity(intent);
            finishNormal();
        }
    }

    /**
     * 创建发送UDP消息到 文件接收方 的服务线程
     * @param serverIP
     */
    private Runnable createSendMsgToServerRunnable(final String serverIP){
        Log.i(TAG, "receiver serverIp ----->>>" + serverIP);
        return new Runnable(){
            @Override
            public void run() {
                try {
                    startFileSenderServer(serverIP, Constant.DEFAULT_SERVER_COM_PORT);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
    }


    /**
     * 开启 文件发送方 通信服务 (必须在子线程执行)
     * @param targetIpAddr
     * @param serverPort
     * @throws Exception
     */
    DatagramSocket mDatagramSocket;
    private void startFileSenderServer(String targetIpAddr, int serverPort) throws Exception{
//        Thread.sleep(3*1000);
        // 确保Wifi连接上之后获取得到IP地址
        int count = 0;
        while(targetIpAddr.equals(Constant.DEFAULT_UNKOWN_IP) && count < Constant.DEFAULT_TRY_TIME){
            Thread.sleep(1000);
            targetIpAddr = MyWifiManager.getInstance(getContext()).getIpAddressFromHotspot();
            Log.i(TAG, "receiver serverIp ----->>>" + targetIpAddr);
            count ++;
        }

        // 即使获取到连接的热点wifi的IP地址也是无法连接网络 所以采取此策略
        count = 0;
        while(!NetUtils.pingIpAddress(targetIpAddr) && count < Constant.DEFAULT_TRY_TIME){
            Thread.sleep(500);
            Log.i(TAG, "try to ping ----->>>" + targetIpAddr + " - " + count );
            count ++;
        }

        mDatagramSocket = new DatagramSocket(null);
        mDatagramSocket.setReuseAddress(true);
        mDatagramSocket.bind(new InetSocketAddress(serverPort));
        byte[] receiveData = new byte[1024];
        byte[] sendData = null;
        InetAddress ipAddress = InetAddress.getByName(targetIpAddr);

        //0.发送 即将发送的文件列表 到文件接收方
        sendFileInfoListToFileReceiverWithUdp(serverPort, ipAddress);

        //1.发送 文件接收方 初始化
        sendData = Constant.MSG_FILE_RECEIVER_INIT.getBytes(BaseTransfer.UTF_8);
        DatagramPacket sendPacket =
                new DatagramPacket(sendData, sendData.length, ipAddress, serverPort);
        mDatagramSocket.send(sendPacket);
        Log.i(TAG, "Send Msg To FileReceiver######>>>" + Constant.MSG_FILE_RECEIVER_INIT);

//        sendFileInfoListToFileReceiverWithUdp(serverPort, ipAddress);


        //2.接收 文件接收方 初始化 反馈
        while(true) {
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            mDatagramSocket.receive(receivePacket);
            String response = new String( receivePacket.getData(), BaseTransfer.UTF_8).trim();
            Log.i(TAG, "Get the msg from FileReceiver######>>>" + response);
            if(response != null && response.equals(Constant.MSG_FILE_RECEIVER_INIT_SUCCESS)){
                // 进入文件发送列表界面 （并且通知文件接收方进入文件接收列表界面）
                mHandler.obtainMessage(MSG_TO_FILE_SENDER_UI).sendToTarget();
            }
        }
    }

    /**
     * 发送即将发送的文件列表到文件接收方
     * @param serverPort
     * @param ipAddress
     * @throws IOException
     */
    private void sendFileInfoListToFileReceiverWithUdp(int serverPort, InetAddress ipAddress) throws IOException {
        //1.1将发送的List<FileInfo> 发送给 文件接收方
        //如何将发送的数据列表封装成JSON
        Map<String, FileInfo> sendFileInfoMap = AppContext.getAppContext().getFileInfoMap();
        List<Map.Entry<String, FileInfo>> fileInfoMapList = new ArrayList<Map.Entry<String, FileInfo>>(sendFileInfoMap.entrySet());
        List<FileInfo> fileInfoList = new ArrayList<FileInfo>();
        //排序
        Collections.sort(fileInfoMapList, Constant.DEFAULT_COMPARATOR);
        for(Map.Entry<String, FileInfo> entry : fileInfoMapList){
            if(entry.getValue() != null ){
                FileInfo fileInfo = entry.getValue();
                String fileInfoStr = FileInfo.toJsonStr(fileInfo);
                DatagramPacket sendFileInfoListPacket =
                        new DatagramPacket(fileInfoStr.getBytes(), fileInfoStr.getBytes().length, ipAddress, serverPort);
                try{
                    mDatagramSocket.send(sendFileInfoListPacket);
                    Log.i(TAG, "sendFileInfoListToFileReceiverWithUdp------>>>" + fileInfoStr + "=== Success!");
                }catch (Exception e){
                    Log.i(TAG, "sendFileInfoListToFileReceiverWithUdp------>>>" + fileInfoStr + "=== Failure!");
                }

            }
        }
    }

    /**
     * 关闭UDP Socket 流
     */
    private void closeSocket(){
        if(mDatagramSocket != null){
            mDatagramSocket.disconnect();
            mDatagramSocket.close();
            mDatagramSocket = null;
        }
    }

}
