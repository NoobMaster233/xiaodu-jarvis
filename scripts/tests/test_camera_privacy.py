"""The actual root capture entry must reject policy-disabled cameras before capture."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]


class CameraPrivacyTests(unittest.TestCase):
    def test_actual_capture_entry_respects_policy_without_opening_camera(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            sources={
                'android/content/Context.java':'''package android.content;
public class Context {public static final String DEVICE_POLICY_SERVICE="device_policy",WINDOW_SERVICE="window";public Object policy;
public Object getSystemService(String name){return policy;}}''',
                'android/app/admin/DevicePolicyManager.java':'''package android.app.admin;
public class DevicePolicyManager {public boolean disabled;
public boolean getCameraDisabled(android.content.ComponentName component){return disabled;}}''',
                'local/xiaodu/speech/CameraPrivacyTest.java':'''package local.xiaodu.speech;
import android.content.Context;import android.app.admin.DevicePolicyManager;
public class CameraPrivacyTest {
 public static void main(String[] args)throws Exception {
  Context c=new Context();DevicePolicyManager p=new DevicePolicyManager();c.policy=p;
  p.disabled=true;
  try{CameraCapture.photo(c);throw new AssertionError("disabled capture passed");}
  catch(CameraCapture.Failure f){if(f.status!=409||!f.code.equals("camera_disabled"))throw new AssertionError();}
  c.policy=null;
  try{CameraCapture.photo(c);throw new AssertionError("missing policy passed");}
  catch(CameraCapture.Failure f){if(f.status!=503||!f.code.equals("camera_policy_unavailable"))throw new AssertionError();}
  c.policy=p;p.disabled=false;
  java.lang.reflect.Method guard=CameraCapture.class.getDeclaredMethod("requireCameraEnabled",Context.class);
  guard.setAccessible(true);guard.invoke(null,c);
  System.out.println("disabled, unavailable and enabled policy checks passed");
 }
}'''}
            paths=[]
            for name,text in sources.items():
                path=folder/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);paths.append(str(path))
            android=ROOT/'.cache/android-mini/android-27.jar'
            if not android.is_file():self.skipTest('先运行 python tools/prepare.py --group android')
            actual=ROOT/'system/speech-http/src/local/xiaodu/speech/CameraCapture.java'
            compiled=subprocess.run(['javac','--release','8','-classpath',str(android),'-d',str(folder),*paths,str(actual)],capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            result=subprocess.run(['java','-cp',str(folder)+':'+str(android),'local.xiaodu.speech.CameraPrivacyTest'],capture_output=True,text=True,timeout=5)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('checks passed',result.stdout)


if __name__=='__main__':unittest.main()
