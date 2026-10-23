package local.xiaodu.voice;

import android.content.Context;
import java.io.*;
import java.net.URI;
import org.json.JSONObject;

/** 可安装包不携带任何家庭地址；用户首次安装时填写自己的桥接地址。 */
public final class ConnectionSettings {
    public static String validate(String value) throws Exception {
        URI uri=new URI(value.trim());
        if(!"http".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/"))||uri.getPort()==0||uri.getPort()>65535)
            throw new IllegalArgumentException("请填写 http://桥接主机:8766，不含密码或路径");
        return value.trim().replaceAll("/+$","");
    }
    public static String load(Context context) {
        try {
            File file=new File(context.getFilesDir(),"connection.json");
            if(!file.isFile()||file.length()>4096)return "";
            byte[] bytes=new byte[(int)file.length()];
            try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(bytes);}
            return validate(new JSONObject(new String(bytes,"UTF-8")).getString("bridge_url"));
        }catch(Exception ignored){return "";}
    }
    public static void save(Context context,String address) throws Exception {
        String valid=validate(address);
        try(FileOutputStream out=context.openFileOutput("connection.json",Context.MODE_PRIVATE)){
            out.write(new JSONObject().put("bridge_url",valid).toString().getBytes("UTF-8"));
        }
    }
}
