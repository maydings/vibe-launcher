package com.maydings.vibelauncher;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny web page on the home network for typing settings on a phone instead of the remote. Runs only
 * while the settings dialog is open; the link carries a random code, so only someone who can see the
 * TV's QR code can send anything.
 */
final class PhoneLink {

    interface Listener {
        /** On the main thread, with the fields the phone filled in (blank ones left out). */
        void onReceived(Map<String, String> fields);
    }

    /**
     * Backups through the page (Settings > General > Backup and restore): one made for the phone to download,
     * and one the phone sends back. On the page's own thread, except restored.
     */
    interface Backups {
        /** The backup to download, as JSON; null when it couldn't be made. */
        String make();

        /** Puts this backup in place; false when it isn't one, or couldn't be. */
        boolean restore(String json);

        /** On the main thread, once a backup from the phone is in place. */
        void restored();
    }

    /**
     * Pairing Wireless debugging (Settings > Apps > Wireless debugging): the code the TV's pairing window shows,
     * typed here because leaving that window on the TV cancels the code.
     */
    interface Pairing {
        /** Pairs with this code (and port, when the phone gave one); what to tell the phone. On the page's thread. */
        String pair(String code, String port);
    }

    /**
     * Custom wallpapers from the phone (Settings > Backdrops > Custom wallpapers > Add from phone): a picture,
     * cut to 16:9 on the phone, or a video, each written straight to a file on the TV as it arrives.
     */
    interface WallpaperUploads {
        /** Where an arriving file goes (Wallpapers.incoming). */
        java.io.File incoming();

        /** Whether a video this big may come (Wallpapers.roomFor). */
        boolean roomFor(long bytes);

        /** The file arrived whole: true when it was added. On the page's thread. */
        boolean received(java.io.File file, boolean video);
    }

    private static final int PORT = 8765;
    /** A wallpaper picture: a 1920 x 1080 JPEG from the phone, well under this. */
    private static final int MAX_WALLPAPER_PICTURE = 30 * 1024 * 1024;
    private static final int MAX_BODY = 8 * 1024;
    /** A tile's picture: the phone sends it already cropped and shrunk to 640 x 360, as a JPEG data URL. */
    private static final int MAX_PICTURE = 1024 * 1024;
    /** A backup: settings are a few kilobytes, each tile picture a few hundred more (as base64). */
    private static final int MAX_BACKUP = 32 * 1024 * 1024;

    private final Listener listener;
    /** Set by Settings: the backup page is there while it is. */
    Backups backups;
    /** Set by Settings on Android 11 and up: the pairing page is there while it is. */
    Pairing pairing;
    /** Set by Settings: the wallpaper page is there while it is. */
    WallpaperUploads wallpapers;
    private final VibeSettings settings;
    /** The app whose tile picture the page is for (tile menu > Edit > Change picture); null for the keys page. */
    private final String pictureFor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String code;
    private ServerSocket server;
    private String url;

    PhoneLink(Listener listener, VibeSettings settings) {
        this(listener, settings, null);
    }

    /** The page for choosing a picture for this app's tile; it arrives as the field "picture", a data URL. */
    PhoneLink(Listener listener, VibeSettings settings, String pictureFor) {
        this.listener = listener;
        this.settings = settings;
        this.pictureFor = pictureFor;
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append("abcdefghjkmnpqrstuvwxyz23456789".charAt(r.nextInt(31)));
        code = sb.toString();
    }

    /** Starts listening; returns the page's address, or null when offline or the port is taken. */
    String start() {
        String ip = localIp();
        if (ip == null) return null;
        try {
            server = new ServerSocket(PORT);
        } catch (IOException e) {
            try {
                server = new ServerSocket(0);   // something else has the usual port
            } catch (IOException e2) {
                return null;
            }
        }
        url = "http://" + ip + ":" + server.getLocalPort() + "/" + code;
        Thread t = new Thread(this::serve, "PhoneLink");
        t.setDaemon(true);
        t.start();
        return url;
    }

    boolean running() {
        return server != null && !server.isClosed();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(5000);
                handle(s);
            } catch (IOException | RuntimeException e) {
                // A dropped connection or the server closing: carry on, or the loop ends.
            }
        }
    }

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] request = head.split("\r\n", 2)[0].split(" ");
        Backups b = backups;
        if (b != null && request.length >= 2 && request[1].startsWith("/" + code + "/backup")) {
            handleBackup(s, in, head, request[0], request[1].substring(code.length() + 1), b);
            return;
        }
        WallpaperUploads w = wallpapers;
        if (w != null && request.length >= 2 && request[1].startsWith("/" + code + "/wallpaper")) {
            handleWallpaper(s, in, head, request[0], request[1], w);
            return;
        }
        Pairing p = pairing;
        if (p != null && request.length >= 2 && request[1].equals("/" + code + "/pair")) {
            if (request[0].equals("POST")) {
                Map<String, String> fields = parseForm(readBody(in, contentLength(head, MAX_BODY)));
                String digits = fields.get("code"), port = fields.get("port");
                if (digits == null || !digits.matches("\\d{6}")) {
                    respondText(s, "400 Bad Request", "Type the 6-digit code the TV shows.");
                } else if (port != null && !port.matches("(.*:)?\\d{1,5}")) {
                    respondText(s, "400 Bad Request", "The port is the number after the colon, such as 37123.");
                } else {
                    String answer;
                    try {
                        answer = p.pair(digits, port);
                    } catch (RuntimeException | Error e) {   // the phone hears what went wrong, not a dropped connection
                        android.util.Log.w("VibeAdb", "pairing failed", e);
                        answer = "Pairing failed on the TV (" + e.getClass().getSimpleName() + "). Try again.";
                    }
                    respondText(s, "200 OK", answer);
                }
            } else {
                respond(s, "200 OK", pairForm());
            }
            return;
        }
        if (request.length < 2 || !request[1].equals("/" + code)) {
            respond(s, "404 Not Found", page("This link has expired. Scan the code on the TV again."));
            return;
        }
        if (request[0].equals("POST") && pictureFor != null) {
            Map<String, String> fields = new HashMap<>();
            fields.put("picture", readBody(in, contentLength(head, MAX_PICTURE)));
            main.post(() -> listener.onReceived(fields));
            respond(s, "200 OK", page("Sent. The picture is on your TV."));
        } else if (request[0].equals("POST")) {
            Map<String, String> fields = parseForm(readBody(in, contentLength(head, MAX_BODY)));
            main.post(() -> listener.onReceived(fields));
            respond(s, "200 OK", page("Sent. The keys are saved on your TV."));
        } else {
            respond(s, "200 OK", pictureFor != null ? pictureForm() : form());
        }
    }

    /**
     * The backup page (/backup), the backup itself to download (/backup.json), and a backup sent back to restore
     * (POST /backup), which is read through before anything on the TV changes.
     */
    private void handleBackup(Socket s, InputStream in, String head, String method, String path, Backups b)
            throws IOException {
        if (method.equals("POST") && path.equals("/backup")) {
            // The phone's backup can take a while to arrive over Wi-Fi: more time than a form's few bytes.
            s.setSoTimeout(30000);
            int length = contentLength(head, MAX_BACKUP + 1);
            if (length > MAX_BACKUP) {
                respondText(s, "413 Payload Too Large", "Too large for a Vibe Launcher backup.");
                return;
            }
            if (b.restore(readBody(in, length))) {
                main.post(b::restored);
                respondText(s, "200 OK", "Restored. Home is starting again on your TV.");
            } else {
                respondText(s, "400 Bad Request", "That isn't a Vibe Launcher backup this version can read.");
            }
        } else if (path.equals("/backup.json")) {
            String json = b.make();
            if (json == null) {
                respondText(s, "500 Internal Server Error", "The backup couldn't be made.");
                return;
            }
            String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Disposition: attachment; "
                    + "filename=\"vibe-launcher-backup-" + day + ".json\"\r\nContent-Length: " + body.length
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } else if (path.equals("/backup")) {
            respond(s, "200 OK", backupForm());
        } else {
            respond(s, "404 Not Found", page("This link has expired. Scan the code on the TV again."));
        }
    }

    /**
     * The wallpaper page, and a picture or video sent from it (POST /wallpaper?kind=photo|video), read straight
     * into a file: a video can be hundreds of megabytes, far more than fits in memory.
     */
    private void handleWallpaper(Socket s, InputStream in, String head, String method, String path,
                                 WallpaperUploads w) throws IOException {
        if (!method.equals("POST")) {
            respond(s, "200 OK", wallpaperForm());
            return;
        }
        boolean video = path.contains("kind=video");
        s.setSoTimeout(60000);   // a big video over Wi-Fi: pauses are normal
        long length = contentLength(head, Integer.MAX_VALUE);
        if (length <= 0) {
            respondText(s, "400 Bad Request", "Nothing arrived. Try again.");
            return;
        }
        if (video ? !w.roomFor(length) : length > MAX_WALLPAPER_PICTURE) {
            respondText(s, "413 Payload Too Large", video
                    ? "Too big: videos can be up to 500 MB, and the TV needs room for them."
                    : "That picture is too big.");
            return;
        }
        java.io.File file = w.incoming();
        long got = 0;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while (got < length && (n = in.read(buf, 0, (int) Math.min(buf.length, length - got))) > 0) {
                out.write(buf, 0, n);
                got += n;
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw e;
        }
        if (got < length) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            respondText(s, "400 Bad Request", "It didn't all arrive. Try again.");
            return;
        }
        if (video) {   // too small: said here, not taken (the page checks first, where the phone can read it)
            int[] size = Wallpapers.videoSize(file);
            if (size != null && Math.min(size[0], size[1]) < Wallpapers.MIN_VIDEO_SIDE) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                respondText(s, "415 Unsupported Media Type", tooSmall(size[0], size[1]));
                return;
            }
        }
        if (w.received(file, video)) {
            respondText(s, "200 OK", video ? "Added. The video is in your wallpapers on the TV."
                    : "Added. The picture is in your wallpapers on the TV.");
        } else {
            respondText(s, "415 Unsupported Media Type", video ? "The TV couldn't use that video."
                    : "The TV couldn't use that picture.");
        }
    }

    /** Why a video under 720p isn't taken, and how to send a bigger one. */
    private static String tooSmall(int w, int h) {
        return "That video is only " + w + " × " + h + ". Videos have to be at least 720p. Videos taken from "
                + "this page come out small, so record in your Camera app and choose the clip here instead.";
    }

    /** The address of the wallpaper page, once started. */
    String wallpaperUrl() {
        return url != null ? url + "/wallpaper" : null;
    }

    private static void respondText(Socket s, String status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** The address of the pairing page, once started. */
    String pairUrl() {
        return url != null ? url + "/pair" : null;
    }

    /** The address of the backup page, once started. */
    String backupUrl() {
        return url != null ? url + "/backup" : null;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b, matched = 0;
        while ((b = in.read()) != -1) {
            out.write(b);
            matched = (b == '\r' || b == '\n') ? matched + 1 : 0;
            if (matched == 4) return out.toString("UTF-8");
            if (out.size() > MAX_BODY) return null;
        }
        return null;
    }

    private static int contentLength(String head, int max) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try {
                    return Math.max(0, Math.min(max, Integer.parseInt(line.substring(15).trim())));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String readBody(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int n = 0, r;
        while (n < length && (r = in.read(buf, n, length - n)) > 0) n += r;
        return new String(buf, 0, n, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim();
                if (!value.isEmpty()) fields.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), value);
            } catch (IOException | IllegalArgumentException ignored) {
            }
        }
        return fields;
    }

    private static void respond(Socket s, String status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static final String STYLE = "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<style>body{font:17px system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:24px;"
            + "max-width:480px}h1{font-weight:500;font-size:24px}label{display:block;margin:20px 0 6px;color:#aaa}"
            + "input{box-sizing:border-box;width:100%;padding:12px;font:inherit;background:#222;color:#fff;"
            + "border:1px solid #444;border-radius:10px}button{margin-top:28px;width:100%;padding:14px;"
            + "font:inherit;font-weight:600;border:0;border-radius:12px;background:#fff;color:#000}"
            + "p{color:#aaa}</style>";

    private String form() {
        return "<!doctype html><title>Vibe Launcher</title>" + STYLE
                + "<h1>Vibe Launcher settings</h1><p>Paste your keys, then tap Send. Leave a field empty to keep "
                + "what the TV already has.</p><form method=post action='/" + code + "'>"
                + field("tmdbKey", "TMDB API key", settings.tmdbKey())
                + field("youtubeKey", "YouTube API key", settings.youtubeKey())
                + field("plexToken", "Plex token", settings.plexToken())
                + field("pexelsKey", "Pexels API key", settings.pexelsKey())
                + "<button>Send to TV</button></form>";
    }

    /**
     * Choosing a tile's picture on the phone, as sites crop a profile picture: the picture behind a fixed
     * 16:9 frame, dragged to pan, pinched (or the slider, or a mouse wheel) to zoom, from the whole of it
     * fitting (Fit) to four times covering the frame. What's in the frame is drawn at 640 x 360 right there,
     * so only a small JPEG crosses the network.
     */
    private String pictureForm() {
        return "<!doctype html><title>Vibe Launcher</title>" + STYLE
                + "<style>canvas{width:100%;border-radius:12px;margin-top:20px;background:#1c1c1e;touch-action:none;"
                + "display:block}input[type=range]{width:100%;margin-top:18px;padding:0;border:0;background:none}"
                + ".row{display:flex;gap:10px;margin-top:14px}.row button{margin-top:0;background:#333;color:#fff}"
                + "#hint{font-size:14px;margin-top:8px}</style>"
                + "<h1>Picture for " + escape(pictureFor) + "</h1>"
                + "<p>Choose a picture, then drag it and pinch to fit the tile.</p>"
                + "<input type=file id=f accept='image/*'>"
                + "<canvas id=c width=640 height=360></canvas>"
                + "<p id=hint></p>"
                + "<input type=range id=z min=0 max=1000 value=0>"
                + "<div class=row><button id=fill>Fill</button><button id=fit>Fit</button></div>"
                + "<button id=send disabled>Send to TV</button><p id=m></p>"
                + "<script>"
                + "var W=640,H=360,img=null,s=1,x=0,y=0,lo=1,hi=1,c=document.getElementById('c'),g=c.getContext('2d'),"
                + "z=document.getElementById('z'),pts={},last=null;"
                // the picture's size on the frame, and where its top left is, kept so it covers the frame where it can
                + "function clamp(){var w=img.width*s,h=img.height*s;"
                + "x=w<=W?(W-w)/2:Math.min(0,Math.max(W-w,x));y=h<=H?(H-h)/2:Math.min(0,Math.max(H-h,y));}"
                + "function draw(){if(!img)return;clamp();g.fillStyle='#1c1c1e';g.fillRect(0,0,W,H);"
                + "g.imageSmoothingQuality='high';g.drawImage(img,x,y,img.width*s,img.height*s);"
                + "z.value=Math.round(1000*Math.log(s/lo)/Math.log(hi/lo));}"
                // zoom to scale n about the point (px,py) of the frame, which stays put
                + "function zoom(n,px,py){n=Math.min(hi,Math.max(lo,n));x=px-(px-x)*n/s;y=py-(py-y)*n/s;s=n;draw();}"
                + "function cover(){return Math.max(W/img.width,H/img.height);}"
                + "function contain(){return Math.min(W/img.width,H/img.height);}"
                + "document.getElementById('f').onchange=function(e){var u=URL.createObjectURL(e.target.files[0]);"
                + "var i=new Image();i.onload=function(){img=i;lo=contain();hi=cover()*4;if(hi<=lo)hi=lo*4;s=cover();"
                + "x=(W-img.width*s)/2;y=(H-img.height*s)/2;draw();document.getElementById('send').disabled=false;"
                + "document.getElementById('hint').textContent='Drag to move, pinch to zoom.';};i.src=u;};"
                + "z.oninput=function(){if(img)zoom(lo*Math.pow(hi/lo,z.value/1000),W/2,H/2);};"
                + "document.getElementById('fill').onclick=function(){if(img){s=cover();x=(W-img.width*s)/2;"
                + "y=(H-img.height*s)/2;draw();}};"
                + "document.getElementById('fit').onclick=function(){if(img){s=contain();draw();}};"
                // frame points from screen points: the canvas is drawn smaller than its 640 x 360
                + "function at(e){var r=c.getBoundingClientRect();return{x:(e.clientX-r.left)*W/r.width,"
                + "y:(e.clientY-r.top)*H/r.height};}"
                + "c.onpointerdown=function(e){c.setPointerCapture(e.pointerId);pts[e.pointerId]=at(e);last=null;};"
                + "c.onpointermove=function(e){if(!img||!pts[e.pointerId])return;var p=at(e),ids=Object.keys(pts);"
                + "if(ids.length==1){x+=p.x-pts[e.pointerId].x;y+=p.y-pts[e.pointerId].y;pts[e.pointerId]=p;draw();return;}"
                + "pts[e.pointerId]=p;var a=pts[ids[0]],b=pts[ids[1]],d=Math.hypot(a.x-b.x,a.y-b.y),"
                + "m={x:(a.x+b.x)/2,y:(a.y+b.y)/2};"
                + "if(last){x+=m.x-last.m.x;y+=m.y-last.m.y;zoom(s*d/last.d,m.x,m.y);}last={d:d,m:m};};"
                + "c.onpointerup=c.onpointercancel=function(e){delete pts[e.pointerId];last=null;};"
                + "c.onwheel=function(e){if(!img)return;e.preventDefault();var p=at(e);"
                + "zoom(s*Math.exp(-e.deltaY*0.0015),p.x,p.y);};"
                + "document.getElementById('send').onclick=function(){var b=this;b.disabled=true;"
                + "fetch(location.pathname,{method:'POST',headers:{'Content-Type':'text/plain'},"
                + "body:c.toDataURL('image/jpeg',0.92)}).then(function(r){return r.text();}).then(function(){"
                + "document.getElementById('m').textContent='Sent. The picture is on your TV.';b.disabled=false;})"
                + ".catch(function(){b.disabled=false;document.getElementById('m').textContent='Not sent: try again.';});};"
                + "</script>";
    }

    /**
     * A wallpaper from the phone: a picture framed 16:9 as a tile's is (drag, pinch, Fill, Fit), drawn at
     * 1920 x 1080 on the phone and sent as a JPEG; or a video, sent as it is, with how far it's got. Both go up
     * as plain bodies (not forms), which the TV writes straight to a file.
     */
    private String wallpaperForm() {
        String min = String.valueOf(Wallpapers.MIN_VIDEO_SIDE);
        return "<!doctype html><title>Vibe Launcher</title>" + STYLE
                + "<style>canvas{width:100%;border-radius:12px;margin-top:20px;background:#1c1c1e;touch-action:none;"
                + "display:block}input[type=range]{width:100%;margin-top:18px;padding:0;border:0;background:none}"
                + ".row{display:flex;gap:10px;margin-top:14px}.row button{margin-top:0;background:#333;color:#fff}"
                + "progress{width:100%;margin-top:16px;height:10px}#hint{font-size:14px;margin-top:8px}"
                + "#note{font-size:14px}</style>"
                + "<h1>Wallpaper for your TV</h1>"
                + "<p>Choose a photo or a video. A photo can be moved and zoomed to fit the screen; a video plays on the "
                + "TV silently, round and round.</p>"
                + "<input type=file id=f accept='image/*,video/*'>"
                + "<div id=crop hidden><canvas id=c width=1920 height=1080></canvas><p id=hint>Drag to move, pinch to zoom.</p>"
                + "<input type=range id=z min=0 max=1000 value=0>"
                + "<div class=row><button id=fill>Fill</button><button id=fit>Fit</button></div></div>"
                + "<button id=send disabled>Send to TV</button><progress id=p max=100 value=0 hidden></progress>"
                + "<p id=m></p>"
                + "<p id=note>Videos: at least 720p, up to 500 MB. 4K video at 60 frames per second may freeze on some TVs; "
                + "4K at 30 fps or 1080p plays smoothly. A video taken from this page comes out small, so record in your "
                + "camera app and choose the clip here.</p>"
                + "<script>"
                + "var W=1920,H=1080,img=null,vid=null,s=1,x=0,y=0,lo=1,hi=1,c=document.getElementById('c'),g=c.getContext('2d'),"
                + "z=document.getElementById('z'),pts={},last=null,send=document.getElementById('send'),"
                + "m=document.getElementById('m'),crop=document.getElementById('crop'),bar=document.getElementById('p');"
                + "function clamp(){var w=img.width*s,h=img.height*s;"
                + "x=w<=W?(W-w)/2:Math.min(0,Math.max(W-w,x));y=h<=H?(H-h)/2:Math.min(0,Math.max(H-h,y));}"
                + "function draw(){if(!img)return;clamp();g.fillStyle='#000';g.fillRect(0,0,W,H);"
                + "g.imageSmoothingQuality='high';g.drawImage(img,x,y,img.width*s,img.height*s);"
                + "z.value=Math.round(1000*Math.log(s/lo)/Math.log(hi/lo));}"
                + "function zoom(n,px,py){n=Math.min(hi,Math.max(lo,n));x=px-(px-x)*n/s;y=py-(py-y)*n/s;s=n;draw();}"
                + "function cover(){return Math.max(W/img.width,H/img.height);}"
                + "function contain(){return Math.min(W/img.width,H/img.height);}"
                // one picker: a photo opens the framing, a video is checked (size, 720p) and sent as it is
                + "document.getElementById('f').onchange=function(e){var f=e.target.files[0];img=null;vid=null;"
                + "send.disabled=true;m.textContent='';crop.hidden=true;if(!f)return;"
                + "if(f.type.indexOf('video')==0||/\\.(mov|mp4|m4v|webm|mkv)$/i.test(f.name)){checkVideo(f);return;}"
                + "var u=URL.createObjectURL(f),i=new Image();i.onload=function(){img=i;lo=contain();hi=cover()*4;"
                + "if(hi<=lo)hi=lo*4;s=cover();x=(W-img.width*s)/2;y=(H-img.height*s)/2;crop.hidden=false;draw();"
                + "send.disabled=false;};i.onerror=function(){m.textContent='That file couldn\\'t be opened as a photo.';};"
                + "i.src=u;};"
                + "function checkVideo(f){if(f.size>500*1024*1024){m.textContent='That video is over 500 MB: choose a "
                + "shorter one.';return;}var e=document.createElement('video'),u=URL.createObjectURL(f),done=false;"
                + "e.preload='metadata';e.muted=true;function ok(){if(done)return;done=true;URL.revokeObjectURL(u);vid=f;"
                + "send.disabled=false;}e.onloadedmetadata=function(){var w=e.videoWidth,h=e.videoHeight;if(done)return;"
                + "if(w&&h&&Math.min(w,h)<" + min + "){done=true;URL.revokeObjectURL(u);m.textContent='That video is only '"
                + "+w+' \\u00D7 '+h+'. Videos have to be at least 720p. Videos taken from this page come out small, so "
                + "record in your camera app and choose the clip here instead.';return;}ok();};"
                + "e.onerror=ok;setTimeout(ok,4000);e.src=u;}"
                + "z.oninput=function(){if(img)zoom(lo*Math.pow(hi/lo,z.value/1000),W/2,H/2);};"
                + "document.getElementById('fill').onclick=function(){if(img){s=cover();x=(W-img.width*s)/2;"
                + "y=(H-img.height*s)/2;draw();}};"
                + "document.getElementById('fit').onclick=function(){if(img){s=contain();draw();}};"
                + "function at(e){var r=c.getBoundingClientRect();return{x:(e.clientX-r.left)*W/r.width,"
                + "y:(e.clientY-r.top)*H/r.height};}"
                + "c.onpointerdown=function(e){c.setPointerCapture(e.pointerId);pts[e.pointerId]=at(e);last=null;};"
                + "c.onpointermove=function(e){if(!img||!pts[e.pointerId])return;var p=at(e),ids=Object.keys(pts);"
                + "if(ids.length==1){x+=p.x-pts[e.pointerId].x;y+=p.y-pts[e.pointerId].y;pts[e.pointerId]=p;draw();return;}"
                + "pts[e.pointerId]=p;var a=pts[ids[0]],b=pts[ids[1]],d=Math.hypot(a.x-b.x,a.y-b.y),"
                + "mm={x:(a.x+b.x)/2,y:(a.y+b.y)/2};"
                + "if(last){x+=mm.x-last.m.x;y+=mm.y-last.m.y;zoom(s*d/last.d,mm.x,mm.y);}last={d:d,m:mm};};"
                + "c.onpointerup=c.onpointercancel=function(e){delete pts[e.pointerId];last=null;};"
                + "c.onwheel=function(e){if(!img)return;e.preventDefault();var p=at(e);"
                + "zoom(s*Math.exp(-e.deltaY*0.0015),p.x,p.y);};"
                // up as a plain body, with how far it's got; the TV's answer is the message
                + "function up(kind,body){send.disabled=true;var q=new XMLHttpRequest();"
                + "q.open('POST',location.pathname+'?kind='+kind);bar.hidden=false;bar.value=0;"
                + "q.upload.onprogress=function(e){if(e.lengthComputable)bar.value=Math.round(100*e.loaded/e.total);};"
                + "q.onload=function(){m.textContent=q.responseText;send.disabled=false;bar.hidden=true;};"
                + "q.onerror=function(){m.textContent='Not sent: try again.';send.disabled=false;bar.hidden=true;};"
                + "m.textContent='Sending…';q.send(body);}"
                + "send.onclick=function(){if(vid){up('video',vid);return;}if(img)c.toBlob(function(b){up('photo',b);},"
                + "'image/jpeg',0.92);};"
                + "</script>";
    }

    /**
     * Backing up to the phone (a download), and restoring from it: a file chosen there is read on the phone,
     * asked about once (it replaces everything), and sent as it is; the TV says whether it took it.
     */
    private String backupForm() {
        return "<!doctype html><title>Vibe Launcher</title>" + STYLE
                + "<style>a.b{display:block;box-sizing:border-box;margin-top:20px;padding:14px;text-align:center;"
                + "font-weight:600;border-radius:12px;background:#fff;color:#000;text-decoration:none}"
                + "h2{font-weight:500;font-size:19px;margin-top:40px}input[type=file]{margin-top:12px}</style>"
                + "<h1>Vibe Launcher backup</h1>"
                + "<p>Settings, tiles, folders, tile pictures and API keys. A backup holds your keys, so keep it "
                + "private.</p>"
                + "<a class=b href='/" + code + "/backup.json' download>Download backup</a>"
                + "<h2>Restore</h2>"
                + "<p>Choose a backup. Everything on the TV is replaced by the backup's, then Home starts again.</p>"
                + "<input type=file id=f accept='.json,application/json'>"
                + "<button id=send disabled>Restore on TV</button><p id=m></p>"
                + "<script>"
                + "var f=document.getElementById('f'),b=document.getElementById('send'),m=document.getElementById('m');"
                + "f.onchange=function(){b.disabled=!f.files.length;m.textContent='';};"
                + "b.onclick=function(){if(!f.files.length)return;"
                + "if(!confirm('Replace all settings, tiles, folders and keys on the TV with this backup?'))return;"
                + "b.disabled=true;m.textContent='Sending…';var r=new FileReader();"
                + "r.onload=function(){fetch(location.pathname,{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:r.result}).then(function(x){return x.text();}).then(function(t){m.textContent=t;b.disabled=false;})"
                + ".catch(function(){m.textContent='Not sent: try again.';b.disabled=false;});};"
                + "r.onerror=function(){m.textContent='The file couldn\\'t be read.';b.disabled=false;};"
                + "r.readAsText(f.files[0]);};"
                + "</script>";
    }

    /**
     * The pairing code, sent as soon as it's typed: the TV's pairing window stays open meanwhile, and its code is
     * only good while it is. The port is only there for when the TV can't find its own pairing window.
     */
    private String pairForm() {
        return "<!doctype html><title>Vibe Launcher</title>" + STYLE
                + "<style>#code{font-size:32px;letter-spacing:10px;text-align:center}details{margin-top:24px;color:#aaa}"
                + "summary{cursor:pointer}</style>"
                + "<h1>Pair Vibe Launcher</h1>"
                + "<p>On the TV, in Developer options, turn on Wireless debugging, open it and choose Pair device "
                + "with pairing code. Type the 6-digit code it shows here.</p>"
                + "<form id=f><label for=code>Pairing code</label>"
                + "<input id=code name=code inputmode=numeric pattern='[0-9]{6}' maxlength=6 autocomplete=one-time-code "
                + "required>"
                + "<details><summary>Didn't pair?</summary><p>Type the port as well: the number after the colon "
                + "under the code on the TV, such as 37123.</p><input id=port name=port inputmode=numeric "
                + "maxlength=21></details>"
                + "<button id=send>Pair</button></form><p id=m></p>"
                + "<script>"
                + "var f=document.getElementById('f'),b=document.getElementById('send'),m=document.getElementById('m');"
                + "f.onsubmit=function(e){e.preventDefault();b.disabled=true;m.textContent='Pairing… this takes a few "
                + "seconds.';fetch(location.pathname,{method:'POST',headers:{'Content-Type':"
                + "'application/x-www-form-urlencoded'},body:new URLSearchParams(new FormData(f)).toString()})"
                + ".then(function(r){return r.text();}).then(function(t){m.textContent=t;b.disabled=false;})"
                + ".catch(function(){m.textContent='Not sent: try again.';b.disabled=false;});};"
                + "</script>";
    }

    /**
     * A field showing what the TV has saved for it, hidden but for the last four characters (anyone on
     * the network who sees the QR code can open this page). Left empty, the saved value stays.
     */
    private static String field(String name, String label, String saved) {
        String hint = saved.isEmpty() ? "Not set"
                : "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" + escape(saved.substring(Math.max(0, saved.length() - 4)));
        return "<label for=" + name + ">" + label + "</label><input id=" + name + " name=" + name
                + " placeholder=\"" + hint + "\" autocomplete=off autocapitalize=off spellcheck=false>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String page(String message) {
        return "<!doctype html><title>Vibe Launcher</title>" + STYLE + "<h1>Vibe Launcher</h1><p>" + message + "</p>";
    }

    /** The QR code for the page's address, black on white, size x size pixels. */
    static Bitmap qr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 0));
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
    }

    /** This device's IPv4 address on the home network. */
    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }
}
