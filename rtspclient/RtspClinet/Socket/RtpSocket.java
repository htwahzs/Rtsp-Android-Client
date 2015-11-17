package com.aaronhan.rtspclient.RtspClinet.Socket;

import android.database.sqlite.SQLiteCantOpenDatabaseException;
import android.util.Log;

import com.aaronhan.rtspclient.RtspClinet.Stream.RtpStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingDeque;

/**
 *RtpSocket is used to set up the rtp socket and receive the data via udp or tcp protocol
 * 1. set up the socket , four different socket : video udp socket, audio udp socket, video tcp socket, audio tcp socket
 * 2. make a thread to get the data form rtp server
 */
public class RtpSocket implements Runnable {
    private final static String tag = "RtpSocket";
    private final static int TRACK_VIDEO = 0x01;
    private final static int TRACK_AUDIO = 0x02;

    private DatagramSocket mUdpSocket;
    private DatagramPacket mUdpPackets;
    private Socket mTcpSocket;
    private BufferedReader mTcpPackets;

    private RtcpSocket mRtcpSocket;
    private Thread mThread;
    private byte[] message = new byte[2048];
    private int port;
    private String ip;
    private boolean isTcptranslate;
    private int trackType;
    private RtpStream mRtpStream;
    private int serverPort;
    private long recordTime = 0;
    private boolean useRtspTcpSocket,isStoped;
    private InputStream  rtspInputStream;
    private LinkedBlockingDeque<byte[]> tcpBuffer = new LinkedBlockingDeque<>();
    private Thread tcpThread;

    private static class rtspPacketInfo {
        public int len;
        public int offset;
        public boolean inNextPacket;
        public byte[] data;
    }
    private rtspPacketInfo rtspBuffer = new rtspPacketInfo();
    private boolean packetFlag;

    public RtpSocket(boolean isTcptranslate, int port, String ip, int serverPort,int trackType) {
        this.port = port;
        this.ip = ip;
        this.trackType = trackType;
        this.isTcptranslate = isTcptranslate;
        this.serverPort = serverPort;
        this.isStoped = false;
        if(serverPort == -1) useRtspTcpSocket = false;
        else if(serverPort == -2) useRtspTcpSocket = true;
        try {
            if(!isTcptranslate) setupUdpSocket();
        }catch ( IOException e ) {
            Log.e(tag, "Start the " + (isTcptranslate ? "tcp" : "udp") + "socket err!");
        }
    }

    public void setRtspSocket(Socket s) {
        try {
            rtspInputStream = s.getInputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setupUdpSocket() throws IOException {
        Log.d(tag,"Start to setup the udp socket , the port is:  " + port + "....");
        mUdpSocket = new DatagramSocket(port);
        mUdpPackets = new DatagramPacket(message,message.length);
        mRtcpSocket = new RtcpSocket(port+1,ip,serverPort+1);
        mRtcpSocket.start();
    }

    private void tcpRecombineThread() {
        tcpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                rtspBuffer.inNextPacket = false;
                int offset;
                while (!Thread.interrupted() && !isStoped) {
                    try {
                        byte[] tcpbuffer = tcpBuffer.take();
                        offset = 0;

                        if(rtspBuffer.inNextPacket) {
                            if(packetFlag) {
                                rtspBuffer.len = ((tcpbuffer[0]&0xFF)<<8)|(tcpbuffer[1]&0xFF);
                                rtspBuffer.data = new byte[rtspBuffer.len];
                                rtspBuffer.offset = 0;
                            }

                            if(rtspBuffer.len > tcpbuffer.length) {
                                System.arraycopy(tcpbuffer,0,rtspBuffer.data,rtspBuffer.offset,tcpbuffer.length);
                                rtspBuffer.offset += tcpbuffer.length;
                                rtspBuffer.len = rtspBuffer.len - tcpbuffer.length;
                                rtspBuffer.inNextPacket = true;
                            } else {
                                System.arraycopy(tcpbuffer, 0, rtspBuffer.data, rtspBuffer.offset, rtspBuffer.len);
                                mRtpStream.receiveData(rtspBuffer.data, rtspBuffer.data.length);
                                offset += rtspBuffer.len;
                                rtspBuffer.inNextPacket = false;
                                analysisOnePacket(tcpbuffer,offset);
                            }
                        }else{
                            analysisOnePacket(tcpbuffer,0);
                        }
                    } catch (InterruptedException e) {
                        Log.e(tag,"The tcp buffer queue is empty..");
                    }
                }
            }
        },"TcpPacketRecombineThread");
        tcpThread.start();
    }

    private void analysisOnePacket(byte[] data, int offset){
        int datalen;
        int[] tmp = getRtspFrameInfo(data,offset);
        byte[] buffer;
        datalen = data.length-tmp[0];
        while(tmp[1] != -1) {
            if(tmp[1] == -2) {
                rtspBuffer.inNextPacket = true;
                packetFlag = true;
                break;
            } else packetFlag = false;
            if(tmp[1] < datalen) {
                //This packet have some rtsp frame
                buffer = new byte[tmp[1]];
                System.arraycopy(data,tmp[0],buffer,0,tmp[1]);
                mRtpStream.receiveData(buffer,buffer.length);
                offset = tmp[0] + tmp[1];
                tmp = getRtspFrameInfo(data,offset);
                datalen = data.length - tmp[0];
                rtspBuffer.inNextPacket = false;
            } else if(tmp[1] > datalen) {
                //This packet have not enough rtsp frame, next packet have some
                rtspBuffer.data = new byte[tmp[1]];
                rtspBuffer.len = tmp[1] - datalen;
                rtspBuffer.offset = datalen;
                if(rtspBuffer.offset != 0){
                    System.arraycopy(data,tmp[0],rtspBuffer.data,0,datalen);
                }
                rtspBuffer.inNextPacket = true;
                break;
            } else if(tmp[1] == datalen) {
                buffer = new byte[tmp[1]];
                System.arraycopy(data,tmp[0], buffer, 0, tmp[1]);
                mRtpStream.receiveData(buffer,buffer.length);
                rtspBuffer.inNextPacket = false;
                break;
            }
        }
    }

    private int[] getRtspFrameInfo(byte[] data,int offset){
        int mOffset,length;
        boolean haveRtspFrame = false;
        for(mOffset = offset; mOffset< data.length-1; mOffset++){
            if(data[mOffset] == 0x24 && data[mOffset+1] == 0x00) {
                haveRtspFrame = true;
                break;
            }
            haveRtspFrame = false;
        }
        if(haveRtspFrame) {
            if(mOffset + 3 < data.length) {
                length = ((data[mOffset + 2] & 0xFF) << 8) | (data[mOffset + 3] & 0xFF);
                mOffset += 4;
            } else length = -2; //This time 0x24 0x00 and data length is not in one packet
        }
        else
            length = -1;
        return new int[]{mOffset, length};
    }

    private void useRtspTcpReading() throws  IOException {
        int len;
        byte[] buffer = new byte[1024*10];
        while((len = rtspInputStream.read(buffer)) != -1) {
            byte[] tcpbuffer = new byte[len];
            System.arraycopy(buffer,0,tcpbuffer,0,len);
            try {
                tcpBuffer.put(tcpbuffer);
            } catch (InterruptedException e) {
                Log.e(tag,"The tcp queue buffer is full.");
            }
        }
    }

    private void setupTcpSocket() throws  IOException {
        Log.d(tag, "Start to setup the tcp socket , the ip is: " + ip + ", the port is: " + port +"....");
        mTcpSocket = new Socket(ip,port);
        mTcpPackets = new BufferedReader(new InputStreamReader(mTcpSocket.getInputStream()));
        Log.d(tag, "setup tcp socket done!");
    }

    public void startRtpSocket() {
        Log.d(tag, "start to run rtp socket thread");
        mThread = new Thread(this,"RTPSocketThread");
        mThread.start();
    }

    private void startTcpReading() throws IOException {
        String readLine;
        while ( (readLine = mTcpPackets.readLine()) != null ) {
            Log.d(tag, "the tcp read data is: " + readLine);
        }
    }

    private void startUdpReading() throws IOException {
        long currentTime;
        mUdpSocket.receive(mUdpPackets);
        byte[] buffer = new byte[mUdpPackets.getLength()];
        System.arraycopy(mUdpPackets.getData(), 0, buffer, 0, mUdpPackets.getLength());
        //Use Rtp stream thread to decode the receive data
        mRtpStream.receiveData(buffer, mUdpPackets.getLength());

        //every 30s send a rtcp packet to server
        currentTime = System.currentTimeMillis();
        if(currentTime-30000 > recordTime) {
            recordTime = currentTime;
            mRtcpSocket.sendReciverReport();
        }
    }

    public void setStream(RtpStream stream) {
        mRtpStream = stream;
    }

    @Override
    public void run() {
        Log.d(tag, "start to get rtp data via socket...");
        try {
            if(isTcptranslate) {
                tcpRecombineThread();
                if(useRtspTcpSocket) {
                    useRtspTcpReading();
                }
                else{
                    setupTcpSocket();
                    startTcpReading();
                }
            } else {
                while ( !Thread.interrupted() )
                    startUdpReading();
            }
        } catch ( IOException e ) {}
    }

    public void stop() throws IOException {
        if(isTcptranslate) {
            if(mTcpSocket != null) {
                mTcpSocket.close();
                mTcpPackets = null;
            }
        }
        else{
            mUdpSocket.close();
            mUdpPackets = null;
        }
        if(mRtcpSocket!=null){
            mRtcpSocket.stop();
            mRtcpSocket = null;
        }
        if(mThread!=null) mThread.interrupt();
        isStoped = true;
        if(rtspBuffer!=null) rtspBuffer=null;
        tcpThread.interrupt();
    }
}
