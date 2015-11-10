package com.aaronhan.rtspclient.RtspClinet.Video;

import com.aaronhan.rtspclient.RtspClinet.Stream.RtpStream;
import com.aaronhan.rtspclient.RtspClient;

/**
 *
 */
public abstract class VideoStream extends RtpStream {
    private final static int NAL_UNIT_TYPE_STAP_A = 24;
    private final static int NAL_UNIT_TYPE_STAP_B = 25;
    private final static int NAL_UNIT_TYPE_MTAP16 = 26;
    private final static int NAL_UNIT_TYPE_MTAP24 = 27;
    private final static int NAL_UNIT_TYPE_FU_A = 28;
    private final static int NAL_UNIT_TYPE_FU_B = 29;

    //NAL unit
    private static int nalType;
    private static int packFlag;
    protected byte[] NALUnit;
    private boolean NALEndFlag;
    private byte[][] buffer = new byte[1024][];
    private int bufferLength;
    protected RtspClient.SDPInfo mSDPinfo;
    private int packetNum = 0;

    public VideoStream() {
        NALEndFlag = false;
        buffer[0] = new byte[1];
    }

    protected abstract void decodeH264Stream();

    @Override
    protected void recombinePacket(StreamPacks streamPacks) {
        if(streamPacks.pt == 96) H264PacketRecombine(streamPacks);
    }

    //This method is used to get the NAL unit from the RTP packet
    //Then translate the NAL unit to the decoder
    private void H264PacketRecombine(StreamPacks streamPacks) {
        int tmpLen;
        nalType = streamPacks.data[0] & 0x1F;
        packFlag = streamPacks.data[1] & 0xC0;

        switch (nalType) {

            //Single-timeaggregation packet
            case NAL_UNIT_TYPE_STAP_A:
                break;

            //Single-timeaggregation packet
            case NAL_UNIT_TYPE_STAP_B:
                break;

            //Multi-time aggregationpacket
            case NAL_UNIT_TYPE_MTAP16:
                break;

            //Multi-time aggregationpacket
            case NAL_UNIT_TYPE_MTAP24:
                break;

            //Fragmentationunit
            case NAL_UNIT_TYPE_FU_A:
                switch (packFlag) {
                    //NAL Unit start packet
                    case 0x80:
                        NALEndFlag = false;
                        packetNum = 1;
                        bufferLength = streamPacks.data.length-1 ;
                        buffer[1] = new byte[streamPacks.data.length-1];
                        buffer[1][0] = (byte)((streamPacks.data[0] & 0xE0)|(streamPacks.data[1]&0x1F));
                        System.arraycopy(streamPacks.data,2,buffer[1],1,streamPacks.data.length-2);
                        break;
                    //NAL Unit middle packet
                    case 0x00:
                        NALEndFlag = false;
                        packetNum++;
                        bufferLength += streamPacks.data.length-2;
                        buffer[packetNum] = new byte[streamPacks.data.length-2];
                        System.arraycopy(streamPacks.data,2,buffer[packetNum],0,streamPacks.data.length-2);
                        break;
                    //NAL Unit end packet
                    case 0x40:
                        NALEndFlag = true;
                        NALUnit = new byte[bufferLength + streamPacks.data.length + 2];
                        NALUnit[0] = 0x00;
                        NALUnit[1] = 0x00;
                        NALUnit[2] = 0x00;
                        NALUnit[3] = 0x01;
                        tmpLen = 4;
                        System.arraycopy(buffer[1], 0, NALUnit, tmpLen, buffer[1].length);
                        tmpLen += buffer[1].length;
                        for(int i = 2; i < packetNum+1; i++) {
                            System.arraycopy(buffer[i],0,NALUnit,tmpLen,buffer[i].length);
                            tmpLen += buffer[i].length;
                        }
                        System.arraycopy(streamPacks.data,2,NALUnit,tmpLen,streamPacks.data.length-2);
                        break;
                }
                break;

            //Fragmentationunit
            case NAL_UNIT_TYPE_FU_B:
                break;

            //Single NAL unit per packet
            default:
                NALUnit = new byte[4+streamPacks.data.length];
                NALUnit[0] = 0x00;
                NALUnit[1] = 0x00;
                NALUnit[2] = 0x00;
                NALUnit[3] = 0x01;
                System.arraycopy(streamPacks.data,0,NALUnit,4,streamPacks.data.length);
                NALEndFlag = true;
                break;
        }
        if(NALEndFlag){
            decodeH264Stream();
        }
    }
}
