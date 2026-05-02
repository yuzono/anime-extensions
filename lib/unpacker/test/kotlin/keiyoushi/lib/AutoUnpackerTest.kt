package keiyoushi.lib

import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.lib.unpacker.Unpacker
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun auto_unpacksWithBase62() {
        assertNotNull(autoUnpacker(PACKED_BASE62))
    }

    @Test
    fun auto_unpacksResult() {
        val packedScript = """eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c]);return p}('0 1',2,2,'test|data'.split('|'),0,{}))"""
        val results = autoUnpacker(packedScript)
        assertNotNull(results)
        assertEquals("test data", results)
    }

    companion object {
        private const val PACKED_CALL = "eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}('0(\\'1 2 3 4 5 6 7\\');',8,8,'alert|This|is|packed|and|a|plain|call'.split('|'),0,{}))"
        private const val PACKED_FUNCTION = "eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;k=[function(e){return r[e]}];e=function(){return'\\\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}('0 1(){2(\\'3 4 5 6 7 0\\')}',8,8,'function|funPackedTest|alert|This|is|packed|and|a'.split('|'),0,{}))"
        private const val PACKED_COMPLEX = """eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('6("4o").4n({4m:[{u:"7://4l.c.m/4k/,4j,.4i/4h.4g"}],4f:"7://p.c.m/n.1q",4e:"d",4d:"1t%",4c:"1t%",4b:"1p",4a:"1s.1r",49:\'48\',47:"d",46:"d",45:"44",43:"11.h",42:"7://c.h",41:"40",b:[{u:"/1l?1k=3z&q=1s.1r&3y=7://p.c.m/3x.1q",3w:"3v"}],3u:{3t:\'#3s\',3r:16,3q:"3p",3o:0,3n:\'1p\',3m:3l},3k:{u:"7://i.3j.3i/3h/3g-3f-3e-3d.j",3c:"7://3b.c.m/3a/39/13/c-38-37-36/",g:"35-34",33:"5"},32:{},31:d,30:[0.5,1,2,3]});a r,t,s=0;a 2z=0,2y=0;a 9=6();9.e(\'2x\',4(x){8(5>0&&x.g>=5&&t!=1){t=1;$(\'f.2w\').2v(\'2u\')}8(s==0&&x.g>=1o&&x.g<=(1o+2)){s=x.g}});9.e(\'2t\',4(x){1n(x)});9.e(\'2s\',4(){$(\'f.1m\').2r()});4 1n(x){$(\'f.1m\').2q();8(r)2p;r=1;l=0;8(z.2o===2n){l=1}$.2m(\'7://y.h/1l?1k=2l&2k=n&2j=2i-2h-1-2g-2f&2e=1&l=\'+l,4(1j){$(\'#2d\').w(1j)});}4 2c(){a b=9.2b(1i);1h.1g(b);8(b.q>1){2a(i=0;i<b.q;i++){8(b[i].29==1i){1h.1g(\'!!=\'+i);9.28(i)}}}}9.e(\'27\',4(){6().o("/1e/26.j","25 10 1d",4(){6().1b(6().1c()+10)},"1f");$("f[1a=1f]").18().17(\'.15-14-12\');6().o("/1e/24.j","23 10 1d",4(){a k=6().1c()-10;8(k<0)k=0;6().1b(k)},"19");$("f[1a=19]").18().17(\'.15-14-12\');});6().e(\'22\',4(){6().21(d)});6().o("/20/1z.j","11 1y",4(){a v=z.1x(\'7://y.h/n.w\',\'1w\');v.1v()},"1u");',36,169,'||||function||jwplayer|https|if|player|var|tracks|vtube|true|on|div|position|to||png|tt|adb|network|3l3u3jvaak54|addButton||length|vvplay|x2ok|vvad|file|win|html||vtbe|window||vTube|rewind||icon|jw||insertAfter|detach|ff00|button|seek|getPosition|sec|player8|ff11|log|console|track_name|data|op|dl|video_ad|doPlay|177|uniform|jpg|09|1421|100|download11|focus|_blank|open|Download|download4|images|setFullscreen|displayClick|Rewind|fr|Forward|ff|ready|setCurrentAudioTrack|name|for|getAudioTracks|set_audio_track|fviews|embed|b4254ca98bf5c178e44631e936ef1ca1|1773681980|200|2013577|hash|file_code|view|get|undefined|cRAds|return|hide|show|complete|play|slow|fadeIn|video_ad_fadein|time|vastdone2|vastdone1|playbackRates|playbackRateControls|cast|margin|right|bottom|vpn|account|premium|07|2023|blog|link|Belle|Sexy|of|Copy|b1jK57B|co|ibb|logo|90|fontOpacity|edgeStyle|backgroundOpacity|Arial|fontFamily|fontSize|FFFFFF|color|captions|thumbnails|kind|3l3u3jvaak540000|url|get_slides|start|startparam|aboutlink|abouttext|html5|primary|hlshtml|androidhls|auto|preload|duration|stretching|height|width|controls|image|m3u8|master|urlset|x5s4zwn6pzyki6cgaptmr6fjgw6peiygskrxu6mlhtd2ihoirklknfaj6tkq|hls|str12|sources|setup|vplayer'.split('|')))"""
        private const val PACKED_BASE62 = """eval(function(p,a,c,k,e,r){e=function(c){return(c<a?'':e(parseInt(c/a)))+((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))};if(!''.replace(/^/,String)){while(c--)r[e(c)]=k[c]||e(c);k=[function(e){return r[e]}];e=function(){return'\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c]);return p}('4("Y").Z({11:[{m:"7://12.b.h/14/,15,.17/18.19"}],1a:"7://p.b.h/n.u",1b:"d",1c:"v%",1d:"v%",1e:"w",1f:"y.z",1g:\'1h\',1i:"d",1j:"d",1k:"1l",1m:"A.j",1n:"7://b.j",1o:"1p",1q:[{m:"/B?C=1r&o=y.z&1s=7://p.b.h/1t.u",1u:"1v"}],1w:{1x:\'#1y\',1z:16,1A:"1B",1C:0,1D:\'w\',1E:1F},1G:{m:"7://i.1H.1I/1J/1K-1L-1M-1N.k",1O:"7://1P.b.h/1Q/1R/13/b-1S-1T-1U/",e:"1V-1W",1X:"5"},1Y:{},1Z:d,20:[0.5,1,2,3]});c q,r,s=0;c 21=0,22=0;c 8=4();8.f(\'23\',6(x){9(5>0&&x.e>=5&&r!=1){r=1;$(\'g.24\').25(\'26\')}9(s==0&&x.e>=D&&x.e<=(D+2)){s=x.e}});8.f(\'27\',6(x){E(x)});8.f(\'28\',6(){$(\'g.F\').29()});6 E(x){$(\'g.F\').2a();9(q)2b;q=1;l=0;9(G.2c===2d){l=1}$.2e(\'7://H.j/B?C=2f&2g=n&2h=2i-2j-1-2k-2l&2m=1&l=\'+l,6(a){$(\'#2n\').I(a)})}6 2o(){c a=8.2p(J);K.L(a);9(a.o>1){2q(i=0;i<a.o;i++){9(a[i].2r==J){K.L(\'!!=\'+i);8.2s(i)}}}}8.f(\'2t\',6(){4().t("/M/2u.k","2v 10 N",6(){4().O(4().P()+10)},"Q");$("g[R=Q]").S().T(\'.U-V-W\');4().t("/M/2w.k","2x 10 N",6(){c a=4().P()-10;9(a<0)a=0;4().O(a)},"X");$("g[R=X]").S().T(\'.U-V-W\')});4().f(\'2y\',6(){4().2z(d)});4().t("/2A/2B.k","A 2C",6(){c a=G.2D(\'7://H.j/n.I\',\'2E\');a.2F()},"2G");',62,167,'||||jwplayer||function|https|player|if||vtube|var|true|position|on|div|network||to|png|adb|file|3l3u3jvaak54|length||vvplay|vvad|x2ok|addButton|jpg|100|uniform||1421|09|vTube|dl|op|177|doPlay|video_ad|window|vtbe|html|track_name|console|log|player8|sec|seek|getPosition|ff11|button|detach|insertAfter|jw|icon|rewind|ff00|vplayer|setup||sources|str12||hls|x5s4zwn6pzyki6cgaptmr6fjgw6peiygskrxu6mlhtd2ihoirklknfaj6tkq||urlset|master|m3u8|image|controls|width|height|stretching|duration|preload|auto|androidhls|hlshtml|primary|html5|abouttext|aboutlink|startparam|start|tracks|get_slides|url|3l3u3jvaak540000|kind|thumbnails|captions|color|FFFFFF|fontSize|fontFamily|Arial|backgroundOpacity|edgeStyle|fontOpacity|90|logo|ibb|co|b1jK57B|Copy|of|Sexy|Belle|link|blog|2023|07|premium|account|vpn|bottom|right|margin|cast|playbackRateControls|playbackRates|vastdone1|vastdone2|time|video_ad_fadein|fadeIn|slow|play|complete|show|hide|return|cRAds|undefined|get|view|file_code|hash|2013577|200|1773681980|b4254ca98bf5c178e44631e936ef1ca1|embed|fviews|set_audio_track|getAudioTracks|for|name|setCurrentAudioTrack|ready|ff|Forward|fr|Rewind|displayClick|setFullscreen|images|download4|Download|open|_blank|focus|download11'.split('|'),0,{}))"""
    }
}
