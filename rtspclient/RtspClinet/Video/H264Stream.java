package com.aaronhan.rtspclient.RtspClinet.Video;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewStub;

import com.aaronhan.rtspclient.RtspClient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingDeque;

/**
 *
 */
public class H264Stream extends VideoStream {
    private final static String tag = "H24Stream";

    private MediaCodec mMeidaCodec;
    private SurfaceView mSurfaceView;
    private ByteBuffer[] inputBuffers;
    private Handler mHandler;
    private LinkedBlockingDeque<byte[]> bufferQueue = new LinkedBlockingDeque<>();
    private int picWidth,picHeight;
    byte[] header_sps,header_pps;
    private boolean isStop;
    private HandlerThread thread;

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)

    public H264Stream(RtspClient.SDPInfo sp) {
        mSDPinfo = sp;
        thread = new HandlerThread("H264StreamThread");
        thread.start();
        mHandler = new Handler(thread.getLooper());
        if(sp.SPS != null) decodeSPS();
    }

    private void configMediaDecoder(){
        if(Build.VERSION.SDK_INT > 15) {
            try {
                mMeidaCodec = MediaCodec.createDecoderByType("Video/AVC");
                MediaFormat mediaFormat = new MediaFormat();
                mediaFormat.setString(MediaFormat.KEY_MIME, "Video/AVC");
                mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(header_sps));
                mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(header_pps));
                mediaFormat.setInteger(MediaFormat.KEY_WIDTH, picWidth);
                mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, picHeight);
                mMeidaCodec.configure(mediaFormat, mSurfaceView.getHolder().getSurface(), null, 0);
                mMeidaCodec.start();
                inputBuffers = mMeidaCodec.getInputBuffers();
                isStop = false;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    public void startMediaHardwareDecode() {
        mHandler.post(hardwareDecodeThread);
    }

    private Runnable hardwareDecodeThread = new Runnable() {
        @Override
        public void run() {
            int mCount = 0;
            int inputBufferIndex,outputBufferIndex;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] tmpByte;
            int framType;
            boolean startKeyFrame = false;

            configMediaDecoder();
            while (!Thread.interrupted() && !isStop) {
                try {
                    tmpByte = bufferQueue.take();
                    framType = tmpByte[4]&0x1F;
                    if(framType == 5) startKeyFrame = true;
                    if(startKeyFrame || framType == 7 || framType == 8) {
                        inputBufferIndex = mMeidaCodec.dequeueInputBuffer(2000);
                        if (inputBufferIndex > 1) {
                            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                            inputBuffer.clear();
                            inputBuffer.put(tmpByte);
                            mMeidaCodec.queueInputBuffer(inputBufferIndex, 0, tmpByte.length, mCount, 0);
                            outputBufferIndex = mMeidaCodec.dequeueOutputBuffer(info, 1000);
                            switch (outputBufferIndex) {
                                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                                    break;
                                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                                    break;
                                case MediaCodec.INFO_TRY_AGAIN_LATER:
                                    break;
                                default:
                                    mMeidaCodec.releaseOutputBuffer(outputBufferIndex, true);
                                    break;
                            }
                            mCount++;
                        }
                    }
                } catch (InterruptedException e) {
                    Log.e(tag,"Wait the buffer come..");
                }
            }
            bufferQueue.clear();
            mMeidaCodec.stop();
            mMeidaCodec.release();
            mMeidaCodec = null;
        }
    };

    public void stop(){
        bufferQueue.clear();
        isStop = true;
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {}
        mHandler.removeCallbacks(hardwareDecodeThread);
        if(mMeidaCodec != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                mMeidaCodec.stop();
                mMeidaCodec.release();
            }
            mMeidaCodec = null;
        }
        super.stop();
        thread.quit();
        mSurfaceView.setVisibility(ViewStub.GONE);
        mSurfaceView.setVisibility(ViewStub.VISIBLE);
    }

    /* This method is used to decode pic width and height from the sps info,
     * which got from the RTSP DESCRIPE request, SDP info.
     */
    private void decodeSPS(){
        int i,offset = 32;
        int pic_width_len,pic_height_len;
        byte[] sps = Base64.decode(mSDPinfo.SPS, Base64.NO_WRAP);
        byte[] pps = Base64.decode(mSDPinfo.PPS, Base64.NO_WRAP);
        int profile_idc = sps[1];
        header_pps = new byte[pps.length];
        header_sps = new byte[sps.length];
        System.arraycopy(sps,0,header_sps,0,sps.length);
        System.arraycopy(pps,0,header_pps,0,pps.length);
        offset += getUeLen(sps,offset);//jump seq_parameter_set_id
        if(profile_idc == 100 || profile_idc == 110 || profile_idc == 122
                || profile_idc == 144) {
            int chroma_format_idc = (getUeLen(sps,offset) == 1)?0:
                    ( sps[(offset+getUeLen(sps,offset))/8] >>
                            (7-((offset+getUeLen(sps,offset))%8)) );
            offset += getUeLen(sps,offset);//jump chroma_format_idc
            if(chroma_format_idc == 3)
                offset++; //jump residual_colour_transform_flag
            offset += getUeLen(sps,offset);//jump bit_depth_luma_minus8
            offset += getUeLen(sps,offset);//jump bit_depth_chroma_minus8
            offset ++; //jump qpprime_y_zero_transform_bypass_flag
            int seq_scaling_matrix_present_flag = (sps[offset/8] >> (8-(offset%8)))&0x01;
            if(seq_scaling_matrix_present_flag == 1) offset += 8; //jump seq_scaling_list_present_flag
        }
        offset += getUeLen(sps,offset);//jump log2_max_frame_num_minus4
        int pic_order_cnt_type = (getUeLen(sps,offset) == 1)?0:
                ( sps[(offset+getUeLen(sps,offset))/8] >>
                        (7-((offset+getUeLen(sps,offset))%8)) );
        offset += getUeLen(sps,offset);
        if(pic_order_cnt_type == 0) {
            offset += getUeLen(sps,offset);
        }
        else if(pic_order_cnt_type == 1) {
            offset++; //jump delta_pic_order_always_zero_flag
            offset += getUeLen(sps,offset); //jump offset_for_non_ref_pic
            offset += getUeLen(sps,offset); //jump offset_for_top_to_bottom_field
            int num_ref_frames_inpic_order_cnt_cycle = ( sps[(offset+getUeLen(sps,offset))/8] >>
                    (7-((offset+getUeLen(sps,offset))%8)) );
            for(i=0; i<num_ref_frames_inpic_order_cnt_cycle; ++i)
                offset += getUeLen(sps,offset); //jump ref_frames_inpic_order
        }
        offset += getUeLen(sps,offset); // jump num_ref_frames
        offset++; // jump gaps_in_fram_num_value_allowed_flag

        pic_width_len = getUeLen(sps,offset);
        picWidth = (getByteBit(sps,offset+pic_width_len/2,pic_width_len/2+1)+1)*16;
        offset += pic_width_len;
        pic_height_len = getUeLen(sps,offset);
        picHeight = (getByteBit(sps,offset+pic_height_len/2,pic_height_len/2+1)+1)*16;
        Log.e(tag, "The picWidth = " + picWidth + " ,the picHeight = " + picHeight);
    }

    private int getUeLen(byte[] bytes, int offset) {
        int zcount = 0;
        while(true) {
            if(( ( bytes[offset/8] >> (7-(offset%8)) ) & 0x01 ) == 0) {
                offset ++;
                zcount ++;
            }
            else break;
        }
        return zcount * 2 + 1;
    }

    /*
     * This method is get the bit[] from a byte[]
     * It may have a more efficient way
     */
    public int getByteBit(byte[] bytes, int offset, int len){
        int tmplen = len/8+ ((len%8+offset%8)>8?1:0) + ((offset%8 == 0)?0:1);
        int lastByteZeroNum = ((len%8+offset%8-8)>0)?(16-len%8-offset%8):(8-len%8-offset%8);
        int data = 0;
        byte tmpC = (byte) (0xFF >> (8 - lastByteZeroNum));
        byte[] tmpB = new byte[tmplen];
        byte[] tmpA = new byte[tmplen];
        int i;
        for(i = 0;i<tmplen;++i) {
            if(i == 0) tmpB[i] = (byte) (bytes[offset/8] << (offset%8) >> (offset%8));
            else if(i+1 == tmplen) tmpB[i] = (byte) ((bytes[offset/8+i] & 0xFF) >> lastByteZeroNum);
            else tmpB[i] = bytes[offset/8+i];
            tmpA[i] = (byte) ((tmpB[i] & tmpC)<<(8-lastByteZeroNum));
            if(i+1 != tmplen && i != 0) {
                tmpB[i] = (byte) ((tmpB[i]&0xFF) >> lastByteZeroNum);
                tmpB[i] = (byte) (tmpB[i] | tmpA[i-1]);
            }
            else if(i == 0) tmpB[0] = (byte) ((tmpB[0]&0xFF) >> lastByteZeroNum);
            else tmpB[i] = (byte) (tmpB[i] | tmpA[i-1]);
            data = ((tmpB[i]&0xFF) << ((tmplen-i-1)*8)) | data ;
        }
        return data;
    }

    public int[] getPicInfo(){
        return new int[]{picWidth, picHeight};
    }

    public void setSurfaceView(SurfaceView s) {
        this.mSurfaceView = s;
        if(Build.VERSION.SDK_INT > 15) {
            startMediaHardwareDecode();
        } else {
            Log.e(tag,"The Platform not support the hardware decode H264!");
        }
    }

    @Override
    protected void decodeH264Stream() {
        try {
            bufferQueue.put(NALUnit);
        } catch (InterruptedException e) {
            Log.e(tag,"The buffer queue is full , wait for the place..");
        }
    }
}
