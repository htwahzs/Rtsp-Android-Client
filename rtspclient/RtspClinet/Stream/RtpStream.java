package com.aaronhan.rtspclient.RtspClinet.Stream;

import android.os.HandlerThread;
import android.os.Handler;
import android.util.Log;

import java.util.concurrent.LinkedBlockingDeque;

/**
 *This class is used to analysis the data from rtp socket , recombine it to video or audio stream
 * 1. get the data from rtp socket
 * 2. put the data into buffer
 * 3. use the thread to get the data from buffer, and unpack it
 */
public abstract class RtpStream {

    private final static String tag = "RtspStream";
    protected final static int TRACK_VIDEO = 0x01;
    protected final static int TRACK_AUDIO = 0x02;

    private Handler mHandler;
    private byte[] buffer;

    protected class StreamPacks {
        public boolean mark;
        public int pt;
        public long timestamp;
        public int sequenceNumber;
        public long Ssrc;
        public byte[] data;
    }

    private static class bufferUnit {
        public byte[] data;
        public int len;
    }

    private static LinkedBlockingDeque<bufferUnit> bufferQueue = new LinkedBlockingDeque<>();

    public RtpStream() {
        HandlerThread thread = new HandlerThread("RTPStreamThread");
        thread.start();
        mHandler = new Handler(thread.getLooper());
        unpackThread();
    }

    public static void receiveData(byte[] data, int len) {
        bufferUnit tmpBuffer = new bufferUnit();
        tmpBuffer.data = new byte[len];
        System.arraycopy(data,0,tmpBuffer.data,0,len);
        tmpBuffer.len = len;
        try {
            bufferQueue.put(tmpBuffer);
        } catch (InterruptedException e) {
        }
    }

    private void unpackThread() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                bufferUnit tmpBuffer;
                while (!Thread.interrupted()) {
                    try {
                        tmpBuffer = bufferQueue.take();
                        buffer = new byte[tmpBuffer.len];
                        System.arraycopy(tmpBuffer.data,0,buffer,0,tmpBuffer.len);
                        unpackData();
                    } catch (InterruptedException e) {
                        Log.e(tag,"wait the new data into the queue..");
                    }
                }
                buffer = null;
                bufferQueue.clear();
            }
        });
    }

    public void stop(){
        bufferQueue.clear();
        buffer = null;
    }


    protected abstract void recombinePacket(StreamPacks sp);

    private void unpackData() {
        StreamPacks tmpStreampack = new StreamPacks();
        int rtpVersion = (buffer[0]&0xFF)>>6;
        if(rtpVersion != 2) {
            Log.e(tag,"This is not a rtp packet.");
            return;
        }
        tmpStreampack.mark = (buffer[1] & 0xFF & 0x80) >> 7 == 1;
        tmpStreampack.pt = buffer[1] & 0x7F;
        tmpStreampack.sequenceNumber = ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        tmpStreampack.timestamp = Long.parseLong(Integer.toHexString(((buffer[4] & 0xFF) << 24) | ((buffer[5]&0xFF) << 16) | ((buffer[6]&0xFF) << 8) | (buffer[7]&0xFF)) , 16);
        tmpStreampack.Ssrc = ((buffer[8]&0xFF) << 24) | ((buffer[9]&0xFF) << 16) | ((buffer[10]&0xFF) << 8) | (buffer[11]&0xFF);
        tmpStreampack.data = new byte[buffer.length-12];

        System.arraycopy(buffer, 12, tmpStreampack.data, 0, buffer.length - 12);

        recombinePacket(tmpStreampack);
    }

}
