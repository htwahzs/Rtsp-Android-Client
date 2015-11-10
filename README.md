# RtspClient

>   - [x] **实现基本框架**
>   - [x] **支持以UDP方式接收RTP报文**
    - [ ] 优化UDP报文解码
>   - [x] **支持以TCP方式接收RTP报文**
    - [ ] 优化TCP报文重组
>   - [x] **实现RTP报文重组**
    - [x] 支持FU-A报文重组
    - [x] 支持单包NAL报文
    - [ ] 增加常用NAL报文重组
>   - [x] **支持H264解码显示**
    - [x] 支持H264硬解码显示(Android 4.1以上版本支持)
    - [ ] 支持H264软解码显示
>   - [ ] **支持ACC音频**

----

#### 使用方法

1. 导入并修改解压包中相应包名
2. 调用ComfdRtspClient包
```java
private SurfaceView mSurfaceView;

//创建client，需要传入一个SurfaceView作为显示
String host = "rtsp://192.168.0.217/test.264"
ComfdRtspClient mRtspClient = new ComfdRtspClient(host);
mRtspClient.setSurfaceView(mSurfaceView);

//开始显示
mRtspClient.start();

//关闭,请在Activity销毁时调用此方法
//在UDP模式下即使销毁Activity某些RTSP服务器也会继续发送报文
mRtspClient.shutdown();
```
3.ComfdRtspClient调用详解
```java
//使用RTP传输协议选择，支持"tcp"和"udp"传入值
String method = "udp";

//传入地址，需以rtsp://开头，支持地址后加入端口地址，"rtsp://ip:port/xxx"
//如未加入端口地址，则使用默认地址554
String host = "rtsp://192.168.0.217/test.264"

//可以单独传入port值，不在地址中增加
//如未传入port只，且地址中没有port，默认使用554
int port = 8554;

//支持传入用户名密码，某些RTSP服务器需要认证使用
String username = "admin";
String password = "admin";

//只传入地址或地址加端口
//默认无用户名密码认证，默认使用udp协议
ComfdRtspClient(host);
ComfdRtspClient(host,port);

//传入使用协议
ComfdRtspClient(method,host);
ComfdRtspClient(method,host,port);

//默认是udp协议，传入认证用户名和密码
ComfdRtspClient(host,username,password);
ComfdRtspClient(host,username,password,port);

//传入使用协议和认证信息
ComfdRtspClient(method,host,username,password);
ComfdRtspClient(method,host,username,password,port);
```
