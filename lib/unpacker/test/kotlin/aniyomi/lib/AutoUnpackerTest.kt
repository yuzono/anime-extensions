package aniyomi.lib

import aniyomi.lib.jsunpacker.JsUnpacker
import aniyomi.lib.unpacker.Unpacker
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AutoUnpackerTest {
    @Test
    fun jsUnpacker_PackedCall() {
        assertNotNull(JsUnpacker.unpackAndCombine(PACKED_CALL))
    }

    @Test
    fun jsUnpacker_PackedFunction() {
        assertNotNull(JsUnpacker.unpackAndCombine(PACKED_FUNCTION))
    }

    @Test
    fun jsUnpacker_PackedComplex() {
        assertNotNull(JsUnpacker.unpackAndCombine(PACKED_COMPLEX))
    }

    @Test
    fun unpacker_PackedCall() {
        assertNotNull(Unpacker.unpack(PACKED_CALL).takeIf(String::isNotBlank))
    }

    @Test
    fun unpacker_PackedFunction() {
        assertNotNull(Unpacker.unpack(PACKED_FUNCTION).takeIf(String::isNotBlank))
    }

    @Test
    fun unpacker_PackedComplex() {
        assertNull(Unpacker.unpack(PACKED_COMPLEX).takeIf(String::isNotBlank))
    }

    @Test
    fun auto_PackedCall() {
        assertNotNull(autoUnpacker(PACKED_CALL))
    }

    @Test
    fun auto_PackedFunction() {
        assertNotNull(autoUnpacker(PACKED_FUNCTION))
    }

    @Test
    fun auto_PackedComplex() {
        assertNotNull(autoUnpacker(PACKED_COMPLEX))
    }

    companion object {
        private const val PACKED_CALL = "eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}('0(\\'1 2 3 4 5 6 7\\');',8,8,'alert|This|is|packed|and|a|plain|call'.split('|'),0,{}))"
        private const val PACKED_FUNCTION = "eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}('0 1(){2(\\'3 4 5 6 7 0\\')}',8,8,'function|funPackedTest|alert|This|is|packed|and|a'.split('|'),0,{}))"
        private const val PACKED_COMPLEX = """eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('6("4o").4n({4m:[{u:"7://4l.c.m/4k/,4j,.4i/4h.4g"}],4f:"7://p.c.m/n.1q",4e:"d",4d:"1t%",4c:"1t%",4b:"1p",4a:"1s.1r",49:\'48\',47:"d",46:"d",45:"44",43:"11.h",42:"7://c.h",41:"40",b:[{u:"/1l?1k=3z&q=1s.1r&3y=7://p.c.m/3x.1q",3w:"3v"}],3u:{3t:\'#3s\',3r:16,3q:"3p",3o:0,3n:\'1p\',3m:3l},3k:{u:"7://i.3j.3i/3h/3g-3f-3e-3d.j",3c:"7://3b.c.m/3a/39/13/c-38-37-36/",g:"35-34",33:"5"},32:{},31:d,30:[0.5,1,2,3]});a r,t,s=0;a 2z=0,2y=0;a 9=6();9.e(\'2x\',4(x){8(5>0&&x.g>=5&&t!=1){t=1;$(\'f.2w\').2v(\'2u\')}8(s==0&&x.g>=1o&&x.g<=(1o+2)){s=x.g}});9.e(\'2t\',4(x){1n(x)});9.e(\'2s\',4(){$(\'f.1m\').2r()});4 1n(x){$(\'f.1m\').2q();8(r)2p;r=1;l=0;8(z.2o===2n){l=1}$.2m(\'7://y.h/1l?1k=2l&2k=n&2j=2i-2h-1-2g-2f&2e=1&l=\'+l,4(1j){$(\'#2d\').w(1j)});}4 2c(){a b=9.2b(1i);1h.1g(b);8(b.q>1){2a(i=0;i<b.q;i++){8(b[i].29==1i){1h.1g(\'!!=\'+i);9.28(i)}}}}9.e(\'27\',4(){6().o("/1e/26.j","25 10 1d",4(){6().1b(6().1c()+10)},"1f");$("f[1a=1f]").18().17(\'.15-14-12\');6().o("/1e/24.j","23 10 1d",4(){a k=6().1c()-10;8(k<0)k=0;6().1b(k)},"19");$("f[1a=19]").18().17(\'.15-14-12\');});6().e(\'22\',4(){6().21(d)});6().o("/20/1z.j","11 1y",4(){a v=z.1x(\'7://y.h/n.w\',\'1w\');v.1v()},"1u");',36,169,'||||function||jwplayer|https|if|player|var|tracks|vtube|true|on|div|position|to||png|tt|adb|network|3l3u3jvaak54|addButton||length|vvplay|x2ok|vvad|file|win|html||vtbe|window||vTube|rewind||icon|jw||insertAfter|detach|ff00|button|seek|getPosition|sec|player8|ff11|log|console|track_name|data|op|dl|video_ad|doPlay|177|uniform|jpg|09|1421|100|download11|focus|_blank|open|Download|download4|images|setFullscreen|displayClick|Rewind|fr|Forward|ff|ready|setCurrentAudioTrack|name|for|getAudioTracks|set_audio_track|fviews|embed|b4254ca98bf5c178e44631e936ef1ca1|1773681980|200|2013577|hash|file_code|view|get|undefined|cRAds|return|hide|show|complete|play|slow|fadeIn|video_ad_fadein|time|vastdone2|vastdone1|playbackRates|playbackRateControls|cast|margin|right|bottom|vpn|account|premium|07|2023|blog|link|Belle|Sexy|of|Copy|b1jK57B|co|ibb|logo|90|fontOpacity|edgeStyle|backgroundOpacity|Arial|fontFamily|fontSize|FFFFFF|color|captions|thumbnails|kind|3l3u3jvaak540000|url|get_slides|start|startparam|aboutlink|abouttext|html5|primary|hlshtml|androidhls|auto|preload|duration|stretching|height|width|controls|image|m3u8|master|urlset|x5s4zwn6pzyki6cgaptmr6fjgw6peiygskrxu6mlhtd2ihoirklknfaj6tkq|hls|str12|sources|setup|vplayer'.split('|')))"""
    }
}
