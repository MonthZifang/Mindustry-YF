package mindustry.server;

import arc.*;
import arc.backend.headless.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.EventType.*;
import mindustry.mod.*;
import mindustry.mod.Mods.*;
import mindustry.net.Net;
import mindustry.net.*;
import mindustry.ui.*;

import java.time.*;

import static arc.util.Log.*;
import static mindustry.Vars.*;
import static mindustry.server.ServerControl.*;

public class ServerLauncher implements ApplicationListener{
    static String[] args;
    private static AdjustableHeadlessApplication application;
    private static volatile int actualTps;

    public static void main(String[] args){
        try{
            ServerLauncher.args = args;
            Vars.platform = new Platform(){};
            Vars.net = new Net(platform.getNet());

            logger = (level1, text) -> {
                String result = "[" + dateTime.format(LocalDateTime.now()) + "] " + format(tags[level1.ordinal()] + " " + text + "&fr");
                System.out.println(result);
            };
            application = new AdjustableHeadlessApplication(new ServerLauncher(), throwable -> CrashHandler.handle(throwable, f -> {}));
        }catch(Throwable t){
            CrashHandler.handle(t, f -> {});
        }
    }

    /** Changes the maximum headless server loop frequency at runtime. */
    public static void setServerTps(int tps){
        if(application != null) application.setMaxTps(tps);
    }

    /** Actual headless application update rate measured over a one-second wall-clock window. */
    public static int actualTps(){
        return Vars.actualServerTps;
    }

    private static final class TpsMonitor implements ApplicationListener{
        private long windowStart = System.nanoTime();
        private int updates;

        @Override
        public void update(){
            updates++;
            long now = System.nanoTime();
            long elapsed = now - windowStart;
            if(elapsed < 1_000_000_000L) return;
            actualTps = (int)Math.round(updates * 1_000_000_000d / elapsed);
            Vars.actualServerTps = actualTps;
            updates = 0;
            windowStart = now;
        }
    }

    private static final class AdjustableHeadlessApplication extends HeadlessApplication{
        AdjustableHeadlessApplication(ApplicationListener listener, arc.func.Cons<Throwable> handler){
            // HeadlessApplication expects seconds per frame, not frames per second.
            super(listener, 1f / 60f, handler);
        }

        void setMaxTps(int tps){
            int value = Math.max(1, tps);
            renderInterval = 1_000_000_000L / value;
        }
    }

    @Override
    public void init(){
        Core.settings.setDataDirectory(Core.files.local("config"));
        loadLocales = false;
        headless = true;

        Vars.loadSettings();
        Vars.init();

        UI.loadColors();
        Fonts.loadContentIconsHeadless();

        content.createBaseContent();
        mods.loadScripts();
        content.createModContent();
        content.init();

        if(mods.hasContentErrors()){
            err("Error occurred loading mod content:");
            for(LoadedMod mod : mods.list()){
                if(mod.hasContentErrors()){
                    err("| &ly[@]", mod.name);
                    for(Content cont : mod.erroredContent){
                        err("| | &y@: &c@", cont.minfo.sourceFile.name(), Strings.getSimpleMessage(cont.minfo.baseError).replace("\n", " "));
                    }
                }
            }
            err("The server will now exit.");
            System.exit(1);
        }

        bases.load();

        Core.app.addListener(new ApplicationListener(){public void update(){ asyncCore.begin(); }});
        Core.app.addListener(logic = new Logic());
        Core.app.addListener(netServer = new NetServer());
        Core.app.addListener(new ServerControl(args));
        Core.app.addListener(new ApplicationListener(){public void update(){ asyncCore.end(); }});
        Core.app.addListener(new TpsMonitor());

        mods.eachClass(Mod::init);
        YZFBridge.bootstrap(args, ServerControl.instance);

        Events.fire(new ServerLoadEvent());
    }
}
