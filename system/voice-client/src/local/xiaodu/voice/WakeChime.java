package local.xiaodu.voice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import local.xiaodu.timing.Milestones;
import static local.xiaodu.timing.Milestones.Point.*;

/** A brief local acknowledgement, before the microphone/remote call starts. */
final class WakeChime {
    static boolean play(Milestones timing){
        if(timing!=null)timing.mark(CHIME_BEGIN);
        boolean completed=false;
        final int rate=16000,frames=3840;
        byte[] pcm=new byte[frames*2];
        for(int n=0;n<frames;n++){
            int part=n/1920,offset=n%1920;
            double envelope=offset<1600?Math.min(1,offset/160.0)*Math.min(1,(1600-offset)/240.0):0;
            short value=(short)(14000*envelope*Math.sin(2*Math.PI*(part==0?880:1175)*n/rate));
            pcm[n*2]=(byte)value;pcm[n*2+1]=(byte)(value>>8);
        }
        AudioTrack player=null;
        try{
            player=new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length).setTransferMode(AudioTrack.MODE_STATIC).build();
            if(player.write(pcm,0,pcm.length)!=pcm.length)return false;
            player.play();if(timing!=null)timing.mark(CHIME_PLAY);long end=SystemClock.elapsedRealtime()+1000;
            while(player.getPlaybackHeadPosition()<frames&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(10);
            completed=player.getPlaybackHeadPosition()>=frames;return completed;
        }catch(RuntimeException failed){return false;}
        finally{if(timing!=null){timing.mark(CHIME_END);if(!completed)timing.mark(CHIME_NOT_COMPLETED);}if(player!=null){try{player.stop();}catch(RuntimeException ignored){}player.release();}if(timing!=null)timing.mark(CHIME_RELEASED);}
    }
}
