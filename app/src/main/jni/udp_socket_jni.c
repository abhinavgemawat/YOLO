//
// Created by Doug Leith on 30/04/2019.
//

#include <jni.h>
#include <sys/socket.h>
#include <unistd.h>
#include <string.h>
#include <netinet/in.h>
#include <errno.h>
#include <arpa/inet.h>
#include <stdio.h>

#define UDPSOCK_METHOD(METHOD_NAME) \
    Java_ie_tcd_netlab_objecttracker_detectors_DetectorYoloHTTP_##METHOD_NAME

#define RECV_TIMEOUT 500000 // 500ms, in us
static const struct timeval timeout={0,RECV_TIMEOUT};
//static const struct timeval timeout={0,2000};//set short timeout for 2 ms (5000us), used for wifi keepalive
struct sockaddr_in servaddr;
#define LISTSIZE 1000
char count[LISTSIZE*2];  // buffers with count part of UDP packet header, precalculated for speed

JNIEXPORT
jint JNICALL UDPSOCK_METHOD(socket)(JNIEnv* env, jobject thiz, jobject addr, jint port){
    int sock_fd;
    if (( sock_fd = socket(AF_INET, SOCK_DGRAM, 0)) < 0 ) {
        return -1;
    }
    // set send buffer size to 5MB (a big number?)
    int sndbuf=5000000;
    if (setsockopt(sock_fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf))<0) {
        return -1;
    }
    // set recv timeout
    if (setsockopt(sock_fd, SOL_SOCKET, SO_RCVTIMEO, (char*)&timeout,sizeof(struct timeval))<0) {
        return -1;
    }

    // store IP address and port for socket (note code below is tied to use of a single socket
    // -- less flexible, but more efficient
    memset((char *) &servaddr, 0, sizeof(servaddr));
    char* IPbuf = (*env)->GetDirectBufferAddress(env, addr);
    memcpy(&servaddr.sin_addr, IPbuf, sizeof(servaddr.sin_addr));
    //inet_aton("10.220.3.233",&servaddr.sin_addr);
    servaddr.sin_family = AF_INET;
    servaddr.sin_port = htons(port);

    // initialise pkt count headers
    uint16_t i;
    for (i=0; i<LISTSIZE; i++) {
        memset(&count[2*i],i%256,1);
        memset(&count[2*i+1],i/256,1);
    }

    return sock_fd;
}

JNIEXPORT
void JNICALL UDPSOCK_METHOD(keepalive)(JNIEnv* env, jobject thiz) {
    // non-blocking socket for sending keep-alives pkts
    int tmp_fd= socket(AF_INET, SOCK_DGRAM, 0);

    // set non-blocking
    fcntl(tmp_fd, F_SETFL, O_NONBLOCK);

    char tmp_buf[1];
    sendto(tmp_fd, tmp_buf, 1, 0, (struct sockaddr *) &servaddr, sizeof(servaddr));

}

JNIEXPORT
jstring JNICALL UDPSOCK_METHOD(sendto)(JNIEnv* env, jobject thiz,
                                    jint sock_fd, jobject bytebuf, jint offset, jint len, jint MSS) {

    char* send_buf = (*env)->GetDirectBufferAddress(env, bytebuf);

    struct mmsghdr msgvec[LISTSIZE];
    struct iovec iovec[LISTSIZE];
    int vlen=0, ret;
    int posn = 0, end;
    while (posn < len) {
        end = posn + MSS;
        if (end > len) end = len;
        msgvec[vlen].msg_hdr.msg_name = &servaddr;
        msgvec[vlen].msg_hdr.msg_namelen = sizeof(servaddr);
        iovec[vlen].iov_base = send_buf+posn;
        iovec[vlen].iov_len = end-posn;
        msgvec[vlen].msg_hdr.msg_iov = &iovec[vlen];
        msgvec[vlen].msg_hdr.msg_iovlen = 1;
        msgvec[vlen].msg_hdr.msg_controllen = 0;
        msgvec[vlen].msg_hdr.msg_control = NULL;
        msgvec[vlen].msg_hdr.msg_flags=0;
        vlen++;
        if (ret<0) break;
        posn = end;
    }
    ret = sendmmsg(sock_fd,&msgvec,vlen,0);
    //ret=sendto(sock_fd, &send_buf[offset], (size_t)len, 0, (struct sockaddr *) &servaddr, sizeof(servaddr));

    char str[1024];
    sprintf(str,"ret=%d errno=%d (%s)",ret,errno,strerror(errno));
    if (ret<=0)
        return (*env)->NewStringUTF(env,str);
    else
        return (*env)->NewStringUTF(env,"Ok");
}

JNIEXPORT
jstring JNICALL UDPSOCK_METHOD(sendmmsg)(JNIEnv* env, jobject thiz,
                                       jint sock_fd, jobject req, jint req_len,
                                       jobject img, jint img_len,
                                       jint MSS) {
    // use scatter gather direct on bytebuffers to carry out segmentation
    // and avoid memcpy's by java

    char* img_buf = (*env)->GetDirectBufferAddress(env, img);
    char* req_buf = (*env)->GetDirectBufferAddress(env, req);

    struct mmsghdr msgvec[LISTSIZE];
    struct iovec iovec[LISTSIZE];
    int vlen=0, mlen=0, ret;

    // first packet is special as it includes request
    //header
    iovec[vlen].iov_base = &count[0];
    iovec[vlen].iov_len = 2;
    vlen++;
    //request
    iovec[vlen].iov_base = req_buf;
    iovec[vlen].iov_len = req_len;
    vlen++;
    //image
    int posn=0, end= MSS-req_len-2;
    if (end >img_len) end = img_len;
    iovec[vlen].iov_base = img_buf+posn;
    iovec[vlen].iov_len = end-posn;

    msgvec[mlen].msg_hdr.msg_name = &servaddr;
    msgvec[mlen].msg_hdr.msg_namelen = sizeof(servaddr);
    msgvec[mlen].msg_hdr.msg_iov = &iovec[0];
    msgvec[mlen].msg_hdr.msg_iovlen = 3;
    msgvec[mlen].msg_hdr.msg_controllen = 0;
    msgvec[mlen].msg_hdr.msg_control = NULL;
    msgvec[mlen].msg_hdr.msg_flags=0;

    // now packetize the remainder of the image, if any
    mlen++; vlen++;
    while ((end < img_len)&&(vlen<LISTSIZE-2)) {
        posn = end;
        end = posn + MSS-2;
        if (end > img_len) end = img_len;
        msgvec[mlen].msg_hdr.msg_name = &servaddr;
        msgvec[mlen].msg_hdr.msg_namelen = sizeof(servaddr);

        msgvec[mlen].msg_hdr.msg_iov = &iovec[vlen];
        msgvec[mlen].msg_hdr.msg_iovlen = 2;
        msgvec[mlen].msg_hdr.msg_controllen = 0;
        msgvec[mlen].msg_hdr.msg_control = NULL;
        msgvec[mlen].msg_hdr.msg_flags=0;

        //header
        iovec[vlen].iov_base = &count[2*mlen];
        iovec[vlen].iov_len = 2;
        vlen++;

        //image
        iovec[vlen].iov_base = img_buf+posn;
        iovec[vlen].iov_len = end-posn;

        mlen++; vlen++;
    }
    ret = sendmmsg(sock_fd,&msgvec,mlen,0);

    char str[1024];
    sprintf(str,"ret=%d errno=%d (%s)",ret,errno,strerror(errno));
    if (ret<=0)
        return (*env)->NewStringUTF(env,str);
    else if (vlen==LISTSIZE-2) {
        return (*env)->NewStringUTF(env,"WARN: in sendmmsg() message too big!");
    } else
        return (*env)->NewStringUTF(env,"Ok");
}


JNIEXPORT
jint JNICALL UDPSOCK_METHOD(recv)(JNIEnv* env, jobject thiz,
                                       jint sock_fd, jobject bytebuf,
                                     jint len, jint MSS) {
    char* recv_buf = (*env)->GetDirectBufferAddress(env, bytebuf);
    int res, tot=0;
    int timedout=0;

    // non-blocking socket for keep-alives
    int tmp_fd;
    if (( tmp_fd = socket(AF_INET, SOCK_DGRAM, 0)) < 0 ) {
        return -errno;
    }
    // set non-blocking
    fcntl(tmp_fd, F_SETFL, O_NONBLOCK);

    // we assume last packet of response will be a less than full sized packet
    // (a bit of a fudge, but we'd be unlucky if not true and if it happens the UDP
    // receive call will in any case timeout safely);
    do {
        res = recvfrom(sock_fd, recv_buf + tot, len - tot, 0, NULL, 0); // we've set recv timeout to 5ms earlier
        if (res > 0) {
            tot += res;
            timedout = 0;
        } else if ((res == 0) || (res < 0 && errno == EAGAIN)) {
            // recvfrom timed out
            timedout += timeout.tv_usec;
            if (timedout >= RECV_TIMEOUT) {
                //a proper timeout, bail
                tot = 0;
                break;
            }
            // keep-alive  timeout
            res = MSS; //so don't exit loop
            // send a packet to wifi interface to try to keep in awake, sigh
            char tmp_buf[1];
            sendto(tmp_fd, tmp_buf, 1, 0, (struct sockaddr *) &servaddr, sizeof(servaddr));
        } else {// error
            tot = -errno;
            break;
        }
    } while (res==MSS);

    close(tmp_fd);

    return tot;
}


JNIEXPORT
void JNICALL UDPSOCK_METHOD(closesocket)(JNIEnv* env, jobject thiz, jint sock_fd){
    close(sock_fd);
}
