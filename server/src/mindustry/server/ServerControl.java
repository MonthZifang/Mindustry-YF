package mindustry.server;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Timer;
import arc.util.CommandHandler.*;
import arc.util.Timer.*;
import arc.util.serialization.*;
import arc.util.serialization.JsonValue.*;
import arc.util.serialization.JsonWriter.*;
import arc.util.serialization.Jval.*;
import mindustry.*;
import mindustry.core.GameState.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.maps.Map;
import mindustry.maps.*;
import mindustry.maps.Maps.*;
import mindustry.mod.Mods.*;
import mindustry.mod.data.*;
import mindustry.net.Administration.*;
import mindustry.net.Packets.*;
import mindustry.net.*;
import mindustry.type.*;
import org.jline.reader.*;
import org.jline.reader.impl.completer.*;
import org.jline.terminal.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.regex.*;

import static arc.util.ColorCodes.*;
import static arc.util.Log.*;
import static mindustry.Vars.*;

public class ServerControl implements ApplicationListener{
    protected static String[] tags = {"&lc&fb[D]&fr", "&lb&fb[I]&fr", "&ly&fb[W]&fr", "&lr&fb[E]", ""};
    protected static DateTimeFormatter dateTime = DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss"),
        autosaveDate = DateTimeFormatter.ofPattern("MM-dd-yyyy_HH-mm-ss");
    protected static final int serverHelpPageSizeDefault = 15;

    static final String defaultRuleString = "reactorExplosions: false\nlogicUnitBuild: false\nlogicUnitDeconstruct: false";

    /** Global instance of ServerControl, initialized when the server is created. Should never be null on a dedicated server. */
    public static ServerControl instance;

    public final CommandHandler handler = new CommandHandler("");
    public final Fi logFolder = Core.settings.getDataDirectory().child("logs/");

    private final Interval autosaveCount = new Interval();

    /** The file to which the logs are currently being written. */
    public Fi currentLogFile;
    private String currentLogDay;
    private static final long managedLogMaxBytes = 5L * 1024L * 1024L;

    /** Whether the server is currently waiting for the next map to be loaded. */
    public boolean inGameOverWait;

    /** The last gamemode loaded on this server. */
    public Gamemode lastMode;

    private Task lastTask;
    private Thread socketThread;
    private ServerSocket serverSocket;
    private final CopyOnWriteArrayList<Socket> socketClients = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PrintWriter> socketOutputs = new CopyOnWriteArrayList<>();
    private final Semaphore socketClientSlots = new Semaphore(32);
    private static final int socketAuthenticationTimeoutMillis = 15_000;
    private static final int socketIdleTimeoutMillis = 300_000;
    private static final int socketMaxLineChars = 16 * 1024;
    private static final int socketMaxCommandsPerMinute = 60;
    private String suggested;
    private boolean autoPaused = false;
    private Fi dataAssetDirectory, rulesFile;
    private Seq<DataAsset> dataAssets = new Seq<>();

    private LineReader lineReader;
    private BufferedReader simpleConsoleReader;
    private boolean simpleConsole;
    private Charset consoleCharset;
    private String configuredConsoleMode, configuredConsoleCharset;
    private volatile boolean shuttingDown;
    private final ObjectMap<String, LocalizedCommandInfo> localizedCommandInfo = new ObjectMap<>();
    private int serverHelpPageSize = serverHelpPageSizeDefault;
    private HelpLanguage helpLanguage = HelpLanguage.zh;

    public Runnable serverInput = () -> {
        while(!shuttingDown){
            try{
                String line = readConsoleLine();
                if(line == null){
                    if(System.console() == null){
                        Log.warn("Console input closed; keeping the server running without interactive input.");
                    }else{
                        Core.app.exit();
                    }
                    break;
                }
                if(!line.isEmpty()){
                    Core.app.post(() -> handleCommandString(line));
                }
            }catch(EndOfFileException | UserInterruptException e){
                Core.app.exit();
                break;
            }catch(Exception e){
                if(shuttingDown) break;
                Core.app.post(() -> { throw new ArcRuntimeException(e); });
            }
        }
    };

    public Cons<GameOverEvent> gameOverListener = event -> {
        if(state.rules.waves){
            info("Game over! Reached wave @ with @ players online on map @.", state.wave, Groups.player.size(), Strings.capitalize(state.map.plainName()));
        }else{
            info("Game over! Team @ is victorious with @ players online on map @.", event.winner.name, Groups.player.size(), Strings.capitalize(state.map.plainName()));
        }

        //set the next map to be played
        Map map = maps.getNextMap(lastMode, state.map);
        if(map != null){
            Call.infoMessage((state.rules.pvp
                    ? "[accent]The " + event.winner.coloredName() + " team is victorious![]\n" : "[scarlet]Game over![]\n")
                    + "\nNext selected map: [accent]" + map.name() + "[white]"
                    + (map.hasTag("author") ? " by[accent] " + map.author() + "[white]" : "") + "." +
                    "\nNew game begins in " + Config.roundExtraTime.num() + " seconds.");

            state.gameOver = true;
            Call.updateGameOver(event.winner);

            info("Selected next map to be @.", map.plainName());

            play(() -> world.loadMap(map, map.applyRules(lastMode)));
        }else{
            netServer.kickAll(KickReason.gameover);
            state.set(State.menu);
            net.closeServer();
        }
    };

    public ServerControl(String[] args){
        setup(args);
        instance = this;
    }

    protected void setup(String[] args){
        registerCommands();
        configureConsoleInput();

        Core.settings.defaults(
            "bans", "",
            "admins", "",
            "shufflemode", "custom"
        );

        //update log level
        Config.debug.set(Config.debug.bool());

        try{
            lastMode = Gamemode.valueOf(Core.settings.getString("lastServerMode", "survival"));
        }catch(Exception e){ //handle enum parse exception
            lastMode = Gamemode.survival;
        }

        logger = (level1, text) -> {
            //err has red text instead of reset.
            if(level1 == LogLevel.err) text = text.replace(reset, lightRed + bold);

            String result = bold + lightBlack + "[" + dateTime.format(LocalDateTime.now()) + "] " + reset + format(tags[level1.ordinal()] + " " + text + "&fr");
            if(lineReader != null && lineReader.isReading()){
                lineReader.callWidget(LineReader.CLEAR);
                lineReader.getTerminal().writer().println(result);
                lineReader.callWidget(LineReader.REDRAW_LINE);
                lineReader.callWidget(LineReader.REDISPLAY);
            }else if(lineReader != null){
                lineReader.getTerminal().writer().println(result);
            }else{
                System.out.println(result);
            }

            if(Config.logging.bool()){
                logToFile("[" + dateTime.format(LocalDateTime.now()) + "] " + formatColors(tags[level1.ordinal()] + " " + text + "&fr", false));
            }

            for(PrintWriter socketOutput : socketOutputs){
                try{
                    socketOutput.println(formatColors(text + "&fr", false));
                    if(socketOutput.checkError()) socketOutputs.remove(socketOutput);
                }catch(Throwable e1){
                    socketOutputs.remove(socketOutput);
                    err("Error occurred logging to socket: @", e1.getClass().getSimpleName());
                }
            }
        };

        formatter = (text, useColors, arg) -> {
            text = Strings.format(text.replace("@", "&fb&lb@&fr"), arg);
            return useColors ? addColors(text) : removeColors(text);
        };

        Time.setDeltaProvider(() -> Math.min(Core.graphics.getDeltaTime() * 60f, maxDeltaServer));

        Core.app.post(() -> {
            //try to load auto-update save if possible
            if(Config.autoUpdate.bool()){
                Fi fi = saveDirectory.child("autosavebe." + saveExtension);
                if(fi.exists()){
                    try{
                        SaveIO.load(fi);
                        info("Auto-save loaded.");
                        state.set(State.playing);
                        netServer.openServer();
                    }catch(Throwable e){
                        err(e);
                    }
                }
            }

            Seq<String> commands = new Seq<>();

            if(args.length > 0){
                commands.addAll(Strings.join(" ", args).split(","));
                info("Found @ command-line arguments to parse.", commands.size);
            }

            if(!Config.startCommands.string().isEmpty()){
                String[] startup = Strings.join(" ", Config.startCommands.string()).split(",");
                info("Found @ startup commands.", startup.length);
                commands.addAll(startup);
            }

            for(String s : commands){
                CommandResponse response = handler.handleMessage(s);
                if(response.type != ResponseType.valid){
                    err("Invalid command argument sent: '@': @", s, response.type.name());
                    err("Argument usage: &lb<command-1> <command1-args...>,<command-2> <command-2-args2...>");
                }
            }
        });

        if(Version.build == -1){
            warn("&lyYour server is running a custom build, which means that client checking is disabled.");
            warn("&lyIt is highly advised to specify which version you're using by building with gradle args &lb&fb-Pbuildversion=&lr<build>");
        }

        customMapDirectory.mkdirs();

        rulesFile = dataDirectory.child("rules.hjson");

        if(!rulesFile.exists() && !Core.settings.has("globalrules")){
            rulesFile.writeString(defaultRuleString);
        }

        //load the old 'globalrules' value
        if(Core.settings.has("globalrules")){
            try{
                Jval base = Jval.newObject();
                if(rulesFile.exists()){
                    base.asObject().putAll(Jval.read(rulesFile.readString()).asObject());
                }
                base.asObject().putAll(Jval.read(Core.settings.getString("globalrules")).asObject());
                rulesFile.writeString(base.toString(Jformat.hjson));

                Core.settings.remove("globalrules");
            }catch(Exception e){
                Log.err("Failed to load previous global rules: ", e);
            }
        }

        dataAssetDirectory = dataDirectory.child("assets");
        dataAssetDirectory.mkdirs();
        loadDataAssets();

        //set up default shuffle mode
        try{
            maps.setShuffleMode(ShuffleMode.valueOf(Core.settings.getString("shufflemode")));
        }catch(Exception e){
            maps.setShuffleMode(ShuffleMode.all);
        }

        Events.on(GameOverEvent.class, event -> {
            if(!inGameOverWait && gameOverListener != null){
                gameOverListener.get(event);
            }
        });

        //reset autosave on world load
        Events.on(WorldLoadEvent.class, e -> {
            autosaveCount.reset(0, Config.autosaveSpacing.num() * 60);
        });

        //autosave periodically
        Events.run(Trigger.update, () -> {
            if(state.isPlaying() && Config.autosave.bool()){
                if(autosaveCount.get(Config.autosaveSpacing.num() * 60)){
                    int max = Config.autosaveAmount.num();

                    //use map file name to make sure it can be saved
                    String mapName = (state.map.file == null ? "unknown" : state.map.file.nameWithoutExtension()).replace(" ", "_");
                    String date = autosaveDate.format(LocalDateTime.now());

                    Seq<Fi> autosaves = saveDirectory.findAll(f -> f.name().startsWith("auto_"));
                    autosaves.sort(f -> -f.lastModified());

                    //delete older saves
                    if(autosaves.size >= max){
                        for(int i = max - 1; i < autosaves.size; i++){
                            autosaves.get(i).delete();
                        }
                    }

                    String fileName = "auto_" + mapName + "_" + date + "." + saveExtension;
                    Fi file = saveDirectory.child(fileName);
                    info("Autosaving...");

                    try{
                        SaveIO.save(file);
                        info("Autosave completed.");
                    }catch(Throwable e){
                        err("Autosave failed.", e);
                    }
                }
            }

            if(state.isGame()){ //run this only if the server's actually hosting
                if(Config.autoPause.bool()){
                    if(Groups.player.isEmpty()){
                        autoPaused = true;
                        state.set(State.paused);
                    }else if(autoPaused){
                        autoPaused = false;
                        state.set(State.playing);
                    }
                }else if(autoPaused && Vars.state.isPaused()){ //unpause when the config is disabled
                    state.set(State.playing);
                    autoPaused = false;
                }
            }
        });

        Events.run(Trigger.socketConfigChanged, () -> {
            toggleSocket(false);
            toggleSocket(Config.socketInput.bool());
        });

        Events.on(ResetEvent.class, e -> {
            autoPaused = false;
        });

        Events.on(PlayEvent.class, e -> {
            try{
                JsonIO.json.readFields(state.rules, readRulesFile());
            }catch(Throwable t){
                err("Error applying custom rules, proceeding without them.", t);
            }
        });

        //autosave settings once a minute
        float saveInterval = 60;
        Timer.schedule(() -> {
            netServer.admins.forceSave();
            Core.settings.forceSave();
        }, saveInterval, saveInterval);

        if(!mods.orderedMods().isEmpty()){
            info("@ mods loaded.", mods.orderedMods().size);
            for(LoadedMod mod : mods.orderedMods()){
                info("  @ &fi@", mod.meta.displayName, mod.meta.version);
            }
        }

        int unsupported = mods.list().count(l -> !l.enabled());

        if(unsupported > 0){
            Log.err("There were errors loading @ mod(s):", unsupported);
            for(LoadedMod mod : mods.list().select(l -> !l.enabled())){
                Log.err("- @ &ly(" + mod.state + ")", mod.meta.name);
            }
        }

        toggleSocket(Config.socketInput.bool());

        Events.on(ServerLoadEvent.class, e -> {
            if(serverInput != null){
                Thread thread = new Thread(serverInput, "Server Controls");
                thread.setDaemon(true);
                thread.start();
            }

            info("Server loaded. Type @ for help.", "'help'");
        });

        Events.on(DataPatchLoadEvent.class, event -> {
            //load server data patches
            for(var asset : dataAssets){
                int index = event.assets.indexOf(d -> d.getType() == asset.getType() && d.path.equalsIgnoreCase(asset.path));
                if(index == -1){
                    event.assets.add(asset);
                }else{
                    event.assets.set(index, asset);
                }
            }
        });
    }

    private void configureConsoleInput(){
        loadConsoleConfiguration();
        consoleCharset = resolveConsoleCharset();
        reconfigureConsoleStreams(consoleCharset);

        if(shouldPreferSimpleConsole()){
            enableSimpleConsole("configured/default compatibility mode");
            return;
        }

        try{
            Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .encoding(consoleCharset)
                .build();

            // JLine accepts a non-interactive stream as a "dumb" terminal, but
            // that terminal does not reliably deliver commands under systemd/docker.
            if("dumb".equalsIgnoreCase(terminal.getType())){
                try{
                    terminal.close();
                }catch(Exception ignored){
                }
                enableSimpleConsole("no interactive terminal detected");
                return;
            }

            lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(handler.getCommandList().map(c -> c.text)))
                .build();
        }catch(Throwable t){
            Log.warn("Falling back to basic server console input: @", Strings.getSimpleMessage(t));
            enableSimpleConsole("terminal initialization failed");
        }
    }

    private boolean shouldPreferSimpleConsole(){
        if(Boolean.getBoolean("mindustry.console.jline")) return false;
        if(Boolean.getBoolean("mindustry.console.simple")) return true;

        String mode = System.getProperty("mindustry.console.mode", "").trim().toLowerCase(Locale.ROOT);
        if(mode.equals("jline")) return false;
        if(mode.equals("simple") || mode.equals("basic")) return true;

        if("jline".equals(configuredConsoleMode)) return false;
        if("simple".equals(configuredConsoleMode) || "basic".equals(configuredConsoleMode)) return true;

        // Dedicated servers commonly run through SSH, tmux, Docker or systemd.
        // Their terminal emulation is not reliable enough for JLine to be the default.
        return true;
    }

    private void enableSimpleConsole(String reason){
        simpleConsole = true;
        simpleConsoleReader = new BufferedReader(new InputStreamReader(System.in, consoleCharset == null ? Charset.defaultCharset() : consoleCharset));
        Log.warn("Using basic server console input (@). Use -Dmindustry.console.mode=jline to force JLine.", reason);
    }

    private Charset resolveConsoleCharset(){
        String configured = System.getProperty("mindustry.console.charset", "").trim();
        if(configured.isEmpty()) configured = configuredConsoleCharset == null ? "" : configuredConsoleCharset;
        if(!configured.isEmpty()){
            try{
                return Charset.forName(configured);
            }catch(Exception e){
                Log.warn("Invalid console charset '@', using default charset instead.", configured);
            }
        }

        Charset detected = detectWindowsConsoleCharset();
        if(detected != null){
            return detected;
        }

        return Charset.defaultCharset();
    }

    private void loadConsoleConfiguration(){
        configuredConsoleMode = "";
        configuredConsoleCharset = "";
        Fi file = Core.settings.getDataDirectory().child("yzf/config/terminal.hjson");
        if(!file.exists()) return;
        try{
            Jval root = Jval.read(YZFBridge.readTextSmart(file));
            configuredConsoleMode = root.getString("consoleMode", "").trim().toLowerCase(Locale.ROOT);
            configuredConsoleCharset = root.getString("charset", "").trim();
        }catch(Exception error){
            Log.warn("Ignoring invalid terminal configuration: @", Strings.getSimpleMessage(error));
        }
    }

    private Charset detectWindowsConsoleCharset(){
        if(!OS.isWindows) return null;

        Charset codePage = detectWindowsCodePage();
        if(codePage != null) return codePage;

        String stdout = System.getProperty("stdout.encoding", "").trim();
        if(!stdout.isEmpty()){
            try{
                return Charset.forName(stdout);
            }catch(Exception ignored){
            }
        }

        String sunStdout = System.getProperty("sun.stdout.encoding", "").trim();
        if(!sunStdout.isEmpty()){
            try{
                return Charset.forName(sunStdout);
            }catch(Exception ignored){
            }
        }

        return null;
    }

    private Charset detectWindowsCodePage(){
        try{
            Process process = new ProcessBuilder("cmd", "/c", "chcp").redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))){
                String line;
                while((line = reader.readLine()) != null){
                    output.append(line).append(' ');
                }
            }
            process.waitFor();

            Matcher matcher = Pattern.compile("(\\d+)").matcher(output);
            if(matcher.find()){
                return Charset.forName("CP" + matcher.group(1));
            }
        }catch(Exception ignored){
        }
        return null;
    }

    private void reconfigureConsoleStreams(Charset charset){
        if(charset == null) return;
        try{
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, charset));
        }catch(Exception e){
            Log.warn("Failed to reconfigure console streams for charset @: @", charset.displayName(), Strings.getSimpleMessage(e));
        }
    }

    private String readConsoleLine() throws IOException{
        if(simpleConsole){
            System.out.print("> ");
            System.out.flush();
            return simpleConsoleReader.readLine();
        }
        return lineReader.readLine("> ");
    }

    JsonValue readRulesFile(){
        return JsonIO.json.fromJson(null, Jval.read(rulesFile.readString()).toString(Jformat.plain));
    }

    void loadDataAssets(){
        Fi oldPatchDirectory = dataDirectory.child("patches");
        if(oldPatchDirectory.exists()){
            Log.warn("Note: Patches are now placed in assets/patches. Any files contained in that directory have been automatically moved.");
            Fi destDir = dataAssetDirectory.child("patches");
            destDir.mkdirs();
            for(Fi file : oldPatchDirectory.list()){
                Fi dest = destDir.child(file.name());
                if(!dest.isDirectory() && dest.exists()){
                    dest = destDir.child("copied_patch_" + file.name());
                }
                if(!file.isDirectory()){
                    file.copyTo(dest);
                }else{
                    file.copyFilesTo(dest);
                }
            }
            oldPatchDirectory.deleteDirectory();
        }

        dataAssets.clear();

        //special folder prefix for server-loaded content. this helps avoid conflicts.
        String prefix = "server-assets/";

        for(var type : DataAssetType.all){
            Fi folder = dataAssetDirectory.child(type.folder);
            folder.mkdirs();

            //content has sub-dirs based on type, which need to be passed as context to the reader
            if(type == DataAssetType.content){
                for(ContentType ctype : ContentAsset.loadableContent){
                    Fi subfolder = folder.child(ctype.folderName);

                    subfolder.mkdirs();

                    Seq<Fi> files = subfolder.findAll(f -> type.extensions.contains(f.extension().toLowerCase(Locale.ROOT)));

                    for(Fi file : files){
                        try{
                            ContentAsset asset = (ContentAsset)type.create();
                            asset.readOverride(prefix + file.absolutePath().substring(subfolder.absolutePath().length() + 1), file, ctype);
                            dataAssets.add(asset);
                        }catch(Throwable e){
                            Log.err("Error loading content asset: " + file, e);
                        }
                    }
                }
            }else{
                Seq<Fi> files = folder.findAll(f -> type.extensions.contains(f.extension().toLowerCase(Locale.ROOT)));

                for(Fi file : files){
                    try{
                        var asset = type.create();
                        asset.readOverride(prefix + file.absolutePath().substring(folder.absolutePath().length() + 1), file);
                        dataAssets.add(asset);
                    }catch(Throwable e){
                        Log.err("Error loading data asset: " + file, e);
                    }
                }
            }
        }

        dataAssets.sort();

        if(dataAssets.size > 0){
            Log.info("Loaded @ data asset files.", dataAssets.size);
            if(dataAssets.count(d -> !d.isAlwaysEmbedded()) >= Short.MAX_VALUE){
                Log.err("Warning: You have more than 32k asset files, which is above the maximum limit. Clients will not be able to connect.");
            }
        }
    }

    protected void registerCommands(){
        handler.register("help", "[command/page/all]", "Display the command list, paginate it, or get help for a specific command.", arg -> {
            if(arg.length == 0){
                printServerHelpPage(1);
                return;
            }

            String target = arg[0];
            if(target.equalsIgnoreCase("all")){
                printAllServerHelp();
                return;
            }

            if(Strings.canParsePositiveInt(target)){
                printServerHelpPage(Math.max(1, Strings.parseInt(target)));
                return;
            }

            Command command = findCommandByTextOrAlias(target);
            if(command == null){
                err("Command '@' not found. Type 'help', 'help 2', or 'help all'.", target);
            }else{
                printServerHelpForCommand(command);
            }
        });

        handler.register("version", "Displays server version info.", arg -> {
            info("Version: Mindustry @-@ @ / build @", Version.number, Version.modifier, Version.type, Version.build + (Version.revision == 0 ? "" : "." + Version.revision));
            info("Java Version: @", OS.javaVersion);
        });

        handler.register("exit", "Exit the server application.", arg -> {
            info("Shutting down server.");
            net.dispose();
            Core.app.exit();
        });

        handler.register("stop", "Stop hosting the server.", arg -> {
            net.closeServer();
            cancelPlayTask();
            state.set(State.menu);
            info("Stopped server.");
        });

        handler.register("host", "[mapname] [mode]", "Open the server. Will default to survival and a random map if not specified.", arg -> {
            if(state.isGame()){
                err("Already hosting. Type 'stop' to stop hosting first.");
                return;
            }

            cancelPlayTask();

            Gamemode preset = Gamemode.survival;

            if(arg.length > 1){
                try{
                    preset = Gamemode.valueOf(arg[1]);
                }catch(IllegalArgumentException e){
                    err("No gamemode '@' found.", arg[1]);
                    return;
                }
            }

            Map result;
            if(arg.length > 0){
                result = maps.all().find(map -> map.plainName().replace('_', ' ').equalsIgnoreCase(Strings.stripColors(arg[0]).replace('_', ' ')));

                if(result == null){
                    err("No map with name '@' found.", arg[0]);
                    return;
                }
            }else{
                result = maps.getShuffleMode().next(preset, state.map);
                if(result != null){
                    info("Randomized next map to be @.", result.plainName());
                }
            }

            info("Loading map...");

            logic.reset();
            if(result != null){
                lastMode = preset;
                Core.settings.put("lastServerMode", lastMode.name());
                try{
                    world.loadMap(result, result.applyRules(lastMode));
                    state.rules = result.applyRules(preset);
                    logic.play();

                    info("Map loaded.");

                    netServer.openServer();
                }catch(MapException e){
                    err("@: @", e.map.plainName(), e.getMessage());
                }
            }
        });

        handler.register("maps", "[all/custom/default]", "Display available maps. Displays only custom maps by default.", arg -> {
            boolean custom = arg.length == 0 || arg[0].equals("custom") || arg[0].equals("all");
            boolean def = arg.length > 0 && (arg[0].equals("default") || arg[0].equals("all"));

            if(!maps.all().isEmpty()){
                Seq<Map> all = new Seq<>();

                if(custom) all.addAll(maps.customMaps());
                if(def) all.addAll(maps.defaultMaps());

                if(all.isEmpty()){
                    info("No custom maps loaded. &fiTo display built-in maps, use the \"@\" argument.", "all");
                }else{
                    info("Maps:");

                    for(Map map : all){
                        String mapName = map.plainName().replace(' ', '_');
                        if(map.custom){
                            info("  @ (@): &fiCustom / @x@", mapName, map.file.name(), map.width, map.height);
                        }else{
                            info("  @: &fiDefault / @x@", mapName, map.width, map.height);
                        }
                    }
                }
            }else{
                info("No maps found.");
            }
            info("Map directory: &fi@", customMapDirectory.file().getAbsoluteFile().toString());
        });

        handler.register("reloadassets", "Reload all content/patch asset files from disk.", arg -> {
            loadDataAssets();
            if(dataAssets.isEmpty()){
                err("No valid asset files found.");
            }
        });

        handler.register("reloadmaps", "Reload all maps from disk.", arg -> {
            int beforeMaps = maps.all().size;
            maps.reload();
            if(maps.all().size > beforeMaps){
                info("@ new map(s) found and reloaded.", maps.all().size - beforeMaps);
            }else if(maps.all().size < beforeMaps){
                info("@ old map(s) deleted.", beforeMaps - maps.all().size);
            }else{
                info("Maps reloaded.");
            }
        });

        handler.register("status", "Display server status.", arg -> {
            if(state.isMenu()){
                info("Status: &rserver closed");
            }else{
                info("Status:");
                info("  Playing on map &fi@ / Wave @", Strings.capitalize(state.map.plainName()), state.wave);

                if(state.rules.waves){
                    info("  @ seconds until next wave.", (int)(state.wavetime / 60));
                }
                info("  @ units / @ enemies", Groups.unit.size(), state.enemies);

                info("  @ FPS, @ MB used.", Core.graphics.getFramesPerSecond(), Core.app.getJavaHeap() / 1024 / 1024);

                if(Groups.player.size() > 0){
                    info("  Players: @", Groups.player.size());
                    for(Player p : Groups.player){
                        info("    @ @ / @", p.admin() ? "&r[A]&c" : "&b[P]&c", p.plainName(), p.uuid());
                    }
                }else{
                    info("  No players connected.");
                }
            }
        });

        handler.register("mods", "Display all loaded mods.", arg -> {
            if(!mods.list().isEmpty()){
                info("Mods:");
                for(LoadedMod mod : mods.list()){
                    info("  @ &fi@ " + (mod.enabled() ? "" : " &lr(" + mod.state + ")"), mod.meta.displayName, mod.meta.version);
                }
            }else{
                info("No mods found.");
            }
            info("Mod directory: &fi@", modDirectory.file().getAbsoluteFile().toString());
        });

        handler.register("mod", "<name...>", "Display information about a loaded plugin.", arg -> {
            LoadedMod mod = mods.list().find(p -> p.meta.name.equalsIgnoreCase(arg[0]));
            if(mod != null){
                info("Name: @", mod.meta.displayName);
                info("Internal Name: @", mod.name);
                info("Version: @", mod.meta.version);
                info("Author: @", mod.meta.author);
                info("Path: @", mod.file.path());
                info("Description: @", mod.meta.description);
            }else{
                info("No mod with name '@' found.", arg[0]);
            }
        });

        handler.register("js", "<script...>", "Run arbitrary Javascript.", arg -> {
            info("&fi&lw&fb" + mods.getScripts().runConsole(arg[0]));
        });

        handler.register("say", "<message...>", "Send a message to all players.", arg -> {
            if(!state.isGame()){
                err("Not hosting. Host a game first.");
                return;
            }

            Call.sendMessage("[scarlet][[Server]:[] " + arg[0]);

            info("&fi&lcServer: &fr@", "&lw" + arg[0]);
        });

        handler.register("pause", "<on/off>", "Pause or unpause the game.", arg -> {
            if(state.isMenu()){
                err("Cannot pause without a game running.");
                return;
            }
            boolean pause = arg[0].equals("on");
            autoPaused = false;
            state.set(pause ? State.paused : State.playing);
            info(pause ? "Game paused." : "Game unpaused.");
        });

        handler.register("rules", "[remove/add] [name] [value...]", "List, remove or add global rules. These will apply regardless of map.", arg -> {
            JsonValue base = readRulesFile();

            if(arg.length == 0){
                info("Rules:\n@", Jval.read(base.toJson(OutputType.minimal)).toString(Jformat.hjson));
            }else if(arg.length == 1){
                err("Invalid usage. Specify which rule to remove or add.");
            }else{
                if(!(arg[0].equals("remove") || arg[0].equals("add"))){
                    err("Invalid usage. Either add or remove rules.");
                    return;
                }

                boolean remove = arg[0].equals("remove");
                if(remove){
                    if(base.has(arg[1])){
                        info("Rule '@' removed.", arg[1]);
                        base.remove(arg[1]);
                    }else{
                        err("Rule not defined, so not removed.");
                        return;
                    }
                }else{
                    if(arg.length < 3){
                        err("Missing last argument. Specify which value to set the rule to.");
                        return;
                    }

                    try{
                        JsonValue value = new JsonReader().parse(arg[2]);
                        value.name = arg[1];

                        JsonValue parent = new JsonValue(ValueType.object);
                        parent.addChild(value);

                        JsonIO.json.readField(state.rules, value.name, parent);
                        if(base.has(value.name)){
                            base.remove(value.name);
                        }
                        base.addChild(arg[1], value);
                        info("Changed rule: @", value.toString().replace("\n", " "));
                    }catch(Throwable e){
                        err("Error parsing rule JSON: @", e.getMessage());
                    }
                }

                rulesFile.writeString(Jval.read(base.toString()).toString(Jformat.hjson));
                Call.setRules(state.rules);
            }
        });

        handler.register("dumpsettings", "Print every settings value. Useful for debugging.", arg -> {
            var allKeys = Seq.with(Core.settings.keys());
            allKeys.sort();
            int maxLength = allKeys.max(String::length).length();
            Log.info("Total values: @ | @ bytes", allKeys.size, Strings.formatByteCount(Core.settings.getSettingsFile().length()));

            for(String key : allKeys){
                var value = Core.settings.get(key, null);

                String valueToString;

                if(value instanceof byte[] b) valueToString = "[" + b.length + " bytes]";
                else valueToString = String.valueOf(value);

                String typeName = switch(value == null ? "null" : value.getClass().getSimpleName()){
                    case "Integer" -> "int   ";
                    case "Boolean" -> "bool  ";
                    case "Float"   -> "float ";
                    case "Long"    -> "long  ";
                    case "byte[]"  -> "byte[]";
                    case "String"  -> "string";
                    default -> value.getClass().getSimpleName();
                };

                Log.info("&lg@  &lg| @&lg |  &lg@", key + " ".repeat(maxLength - key.length()), typeName, valueToString);
            }
        });

        handler.register("fillitems", "[team]", "Fill the core with items.", arg -> {
            if(!state.isGame()){
                err("Not playing. Host first.");
                return;
            }

            Team team = arg.length == 0 ? Team.sharded : Structs.find(Team.all, t -> t.name.equals(arg[0]));

            if(team == null){
                err("No team with that name found.");
                return;
            }

            if(state.teams.cores(team).isEmpty()){
                err("That team has no cores.");
                return;
            }

            for(Item item : content.items()){
                state.teams.cores(team).first().items.set(item, state.teams.cores(team).first().storageCapacity);
            }

            info("Core filled.");
        });

        handler.register("playerlimit", "[off/somenumber]", "Set the server player limit.", arg -> {
            if(arg.length == 0){
                info("Player limit is currently @.", netServer.admins.getPlayerLimit() == 0 ? "off" : netServer.admins.getPlayerLimit());
                return;
            }
            if(arg[0].equals("off")){
                netServer.admins.setPlayerLimit(0);
                info("Player limit disabled.");
                return;
            }

            if(Strings.canParsePositiveInt(arg[0]) && Strings.parseInt(arg[0]) > 0){
                int lim = Strings.parseInt(arg[0]);
                netServer.admins.setPlayerLimit(lim);
                info("Player limit is now &lc@.", lim);
            }else{
                err("Limit must be a number above 0.");
            }
        });

        handler.register("config", "[name] [value...]", "Configure server settings.", arg -> {
            if(arg.length == 0){
                info("All config values:");
                for(Config c : Config.all){
                    info("&lk| @: @", c.name, "&lc&fi" + c.get());
                    info("&lk| | &lw" + c.description);
                    info("&lk|");
                }
                return;
            }

            Config c = Config.all.find(conf -> conf.name.equalsIgnoreCase(arg[0]));

            if(c != null){
                if(arg.length == 1){
                    info("'@' is currently @.", c.name, c.get());
                }else{
                    if(arg[1].equals("default")){
                        c.set(c.defaultValue);
                    }else if(c.isBool()){
                        c.set(arg[1].equals("on") || arg[1].equals("true"));
                    }else if(c.isNum()){
                        try{
                            c.set(Integer.parseInt(arg[1]));
                        }catch(NumberFormatException e){
                            err("Not a valid number: @", arg[1]);
                            return;
                        }
                    }else if(c.isString()){
                        c.set(arg[1].replace("\\n", "\n"));
                    }

                    info("@ set to @.", c.name, c.get());
                    Core.settings.forceSave();
                }
            }else{
                err("Unknown config: '@'. Run the command with no arguments to get a list of valid configs.", arg[0]);
            }
        });

        handler.register("subnet-ban", "[add/remove] [address]", "Ban a subnet. This simply rejects all connections with IPs starting with some string.", arg -> {
            if(arg.length == 0){
                info("Subnets banned: @", netServer.admins.getSubnetBans().isEmpty() ? "<none>" : "");
                for(String subnet : netServer.admins.getSubnetBans()){
                    info("&lw\t" + subnet);
                }
            }else if(arg.length == 1){
                err("You must provide a subnet to add or remove.");
            }else{
                if(arg[0].equals("add")){
                    if(netServer.admins.getSubnetBans().contains(arg[1])){
                        err("That subnet is already banned.");
                        return;
                    }

                    netServer.admins.addSubnetBan(arg[1]);
                    info("Banned @**", arg[1]);
                }else if(arg[0].equals("remove")){
                    if(!netServer.admins.getSubnetBans().contains(arg[1])){
                        err("That subnet isn't banned.");
                        return;
                    }

                    netServer.admins.removeSubnetBan(arg[1]);
                    info("Unbanned @**", arg[1]);
                }else{
                    err("Incorrect usage. Provide add/remove as the second argument.");
                }
            }
        });

        handler.register("name-ban", "[add/remove/clear] [regex]", "Ban a name by case-insensitive regex.", arg -> {
            var names = netServer.admins.bannedNames;

            if(arg.length == 0){
                info("Name regexes banned: @", names.isEmpty() ? "<none>" : "");
                for(Pattern subnet : names){
                    info("&lw\t" + subnet.pattern());
                }
            }else if(arg.length == 1){
                if(arg[0].equals("clear")){
                    names.clear();
                    netServer.admins.save();
                }else{
                    err("You must provide a name regex to add or remove.");
                }
            }else{
                if(arg[0].equals("add")){
                    if(names.contains(p -> p.pattern().equals(arg[1]))){
                        err("That name regex is already banned.");
                        return;
                    }

                    try{
                        netServer.admins.addNameBan(arg[1]);
                        info("Banned names by regex: @", arg[1]);
                    }catch(Exception e){
                        err("Invalid regex: @", Strings.getSimpleMessage(e));
                    }
                }else if(arg[0].equals("remove")){
                    int target = names.indexOf(p -> p.pattern().equals(arg[1]));
                    if(target == -1){
                        err("That name isn't banned.");
                        return;
                    }

                    names.remove(target);
                    netServer.admins.save();
                    info("Unbanned regex: @", arg[1]);
                }else{
                    err("Incorrect usage. Provide add/remove as the second argument.");
                }
            }
        });

        handler.register("whitelist", "[add/remove] [ID]", "Add/remove players from the whitelist using their ID.", arg -> {
            if(arg.length == 0){
                Seq<PlayerInfo> whitelist = netServer.admins.getWhitelisted();

                if(whitelist.isEmpty()){
                    info("No whitelisted players found.");
                }else{
                    info("Whitelist:");
                    whitelist.each(p -> info("- Name: @ / UUID: @", p.plainLastName(), p.id));
                }
            }else{
                if(arg.length == 2){
                    PlayerInfo info = netServer.admins.getInfoOptional(arg[1]);

                    if(info == null){
                        err("Player ID not found. You must use the ID displayed when a player joins a server.");
                    }else{
                        if(arg[0].equals("add")){
                            netServer.admins.whitelist(arg[1]);
                            info("Player '@' has been whitelisted.", info.plainLastName());
                        }else if(arg[0].equals("remove")){
                            netServer.admins.unwhitelist(arg[1]);
                            info("Player '@' has been un-whitelisted.", info.plainLastName());
                        }else{
                            err("Incorrect usage. Provide add/remove as the second argument.");
                        }
                    }
                }else{
                    err("Incorrect usage. Provide an ID to add or remove.");
                }
            }
        });

        //TODO should be a config, not a separate command.
        handler.register("shuffle", "[none/all/custom/builtin]", "Set map shuffling mode.", arg -> {
            if(arg.length == 0){
                info("Shuffle mode current set to '@'.", maps.getShuffleMode());
            }else{
                try{
                    ShuffleMode mode = ShuffleMode.valueOf(arg[0]);
                    Core.settings.put("shufflemode", mode.name());
                    maps.setShuffleMode(mode);
                    info("Shuffle mode set to '@'.", arg[0]);
                }catch(Exception e){
                    err("Invalid shuffle mode.");
                }
            }
        });

        handler.register("nextmap", "<mapname...>", "Set the next map to be played after a game-over. Overrides shuffling.", arg -> {
            Map res = maps.all().find(map -> map.plainName().replace('_', ' ').equalsIgnoreCase(Strings.stripColors(arg[0]).replace('_', ' ')));
            if(res != null){
                maps.setNextMapOverride(res);
                info("Next map set to '@'.", res.plainName());
            }else{
                err("No map '@' found.", arg[0]);
            }
        });

        handler.register("kick", "<username...>", "Kick a person by name.", arg -> {
            if(!state.isGame()){
                err("Not hosting a game yet. Calm down.");
                return;
            }

            Player target = Groups.player.find(p -> p.name().equals(arg[0]));

            if(target != null){
                Call.sendMessage("[scarlet]" + target.name() + "[scarlet] has been kicked by the server.");
                target.kick(KickReason.kick);
                info("It is done.");
            }else{
                info("Nobody with that name could be found...");
            }
        });

        handler.register("ban", "<type-id/name/ip> <username/IP/ID...>", "Ban a person.", arg -> {
            if(arg[0].equals("id")){
                netServer.admins.banPlayerID(arg[1]);
                info("Banned.");
            }else if(arg[0].equals("name")){
                Player target = Groups.player.find(p -> p.name().equalsIgnoreCase(arg[1]));
                if(target != null){
                    netServer.admins.banPlayer(target.uuid());
                    info("Banned.");
                }else{
                    err("No matches found.");
                }
            }else if(arg[0].equals("ip")){
                netServer.admins.banPlayerIP(arg[1]);
                info("Banned.");
            }else{
                err("Invalid type.");
            }

            for(Player player : Groups.player){
                if(netServer.admins.isIDBanned(player.uuid())){
                    Call.sendMessage("[scarlet]" + player.name + " has been banned.");
                    player.con.kick(KickReason.banned);
                }
            }
        });

        handler.register("bans", "List all banned IPs and IDs.", arg -> {
            Seq<PlayerInfo> bans = netServer.admins.getBanned();

            if(bans.size == 0){
                info("No ID-banned players have been found.");
            }else{
                info("Banned players [ID]:");
                for(PlayerInfo info : bans){
                    info(" @ / Last known name: '@'", info.id, info.plainLastName());
                }
            }

            Seq<String> ipbans = netServer.admins.getBannedIPs();

            if(ipbans.size == 0){
                info("No IP-banned players have been found.");
            }else{
                info("Banned players [IP]:");
                for(String string : ipbans){
                    PlayerInfo info = netServer.admins.findByIP(string);
                    if(info != null){
                        info("  '@' / Last known name: '@' / ID: '@'", string, info.plainLastName(), info.id);
                    }else{
                        info("  '@' (No known name or info)", string);
                    }
                }
            }
        });

        handler.register("unban", "<ip/ID>", "Completely unban a person by IP or ID.", arg -> {
            if(netServer.admins.unbanPlayerIP(arg[0]) || netServer.admins.unbanPlayerID(arg[0])){
                info("Unbanned player: @", arg[0]);
            }else{
                err("That IP/ID is not banned!");
            }
        });

        handler.register("pardon", "<ID>", "Pardons a votekicked player by ID and allows them to join again.", arg -> {
            PlayerInfo info = netServer.admins.getInfoOptional(arg[0]);

            if(info != null){
                info.lastKicked = 0;
                netServer.admins.kickedIPs.remove(info.lastIP);
                info("Pardoned player: @", info.plainLastName());
            }else{
                err("That ID can't be found.");
            }
        });

        handler.register("admin", "<add/remove> <username/ID...>", "Make an online user admin", arg -> {
            if(!state.isGame()){
                err("Open the server first.");
                return;
            }

            if(!(arg[0].equals("add") || arg[0].equals("remove"))){
                err("Second parameter must be either 'add' or 'remove'.");
                return;
            }

            boolean add = arg[0].equals("add");

            PlayerInfo target;
            Player playert = Groups.player.find(p -> p.plainName().equalsIgnoreCase(Strings.stripColors(arg[1])));
            if(playert != null){
                target = playert.getInfo();
            }else{
                target = netServer.admins.getInfoOptional(arg[1]);
                playert = Groups.player.find(p -> p.getInfo() == target);
            }

            if(target != null){
                if(add){
                    netServer.admins.adminPlayer(target.id, playert == null ? target.adminUsid : playert.usid());
                }else{
                    netServer.admins.unAdminPlayer(target.id);
                }
                if(playert != null) playert.admin = add;
                info("Changed admin status of player: @", target.plainLastName());
            }else{
                err("Nobody with that name or ID could be found. If adding an admin by name, make sure they're online; otherwise, use their UUID.");
            }
        });

        handler.register("admins", "List all admins.", arg -> {
            Seq<PlayerInfo> admins = netServer.admins.getAdmins();

            if(admins.size == 0){
                info("No admins have been found.");
            }else{
                info("Admins:");
                for(PlayerInfo info : admins){
                    info(" &lm @ /  ID: '@' / IP: '@'", info.plainLastName(), info.id, info.lastIP);
                }
            }
        });

        handler.register("players", "List all players currently in game.", arg -> {
            if(Groups.player.size() == 0){
                info("No players are currently in the server.");
            }else{
                info("Players: @", Groups.player.size());
                for(Player user : Groups.player){
                    info(" @&lm @ / ID: @ / IP: @", user.admin ? "&r[A]&c" : "&b[P]&c", user.plainName(), user.uuid(), user.ip());
                }
            }
        });

        handler.register("runwave", "Trigger the next wave.", arg -> {
            if(!state.isGame()){
                err("Not hosting. Host a game first.");
            }else{
                logic.runWave();
                info("Wave spawned.");
            }
        });

        handler.register("loadautosave", "Loads the last auto-save.", arg -> {
            if(state.isGame()){
                err("Already hosting. Type 'stop' to stop hosting first.");
                return;
            }

            Fi newestSave = saveDirectory.findAll(f -> f.name().startsWith("auto_")).min(Fi::lastModified);

            if(newestSave == null){
                err("No auto-saves found! Type `config autosave true` to enable auto-saves.");
                return;
            }

            if(!SaveIO.isSaveValid(newestSave)){
                err("No (valid) save data found for slot.");
                return;
            }

            Core.app.post(() -> {
                try{
                    SaveIO.load(newestSave);
                    state.rules.sector = null;
                    info("Save loaded.");
                    state.set(State.playing);
                    netServer.openServer();
                }catch(Throwable t){
                    err("Failed to load save. Outdated or corrupt file.");
                }
            });
        });

        handler.register("load", "<slot>", "Load a save from a slot.", arg -> {
            if(state.isGame()){
                err("Already hosting. Type 'stop' to stop hosting first.");
                return;
            }

            Fi file = saveDirectory.child(arg[0] + "." + saveExtension);

            if(!SaveIO.isSaveValid(file)){
                err("No (valid) save data found for slot.");
                return;
            }

            Core.app.post(() -> {
                try{
                    SaveIO.load(file);
                    state.rules.sector = null;
                    info("Save loaded.");
                    state.set(State.playing);
                    netServer.openServer();
                }catch(Throwable t){
                    err("Failed to load save. Outdated or corrupt file.");
                }
            });
        });

        handler.register("save", "<slot> [embedAssets]", "Save game state to a slot.", arg -> {
            if(!state.isGame()){
                err("Not hosting. Host a game first.");
                return;
            }

            Fi file = saveDirectory.child(arg[0] + "." + saveExtension);

            Core.app.post(() -> {
                SaveIO.save(file, new SaveOptions(){{
                    embedAssets = arg.length > 1 && ("true".equalsIgnoreCase(arg[1]) || "yes".equalsIgnoreCase(arg[1]));
                }});
                info("Saved to @.", file);
            });
        });

        handler.register("saves", "List all saves in the save directory.", arg -> {
            info("Save files: ");
            for(Fi file : saveDirectory.list()){
                if(file.extension().equals(saveExtension)){
                    info("| @", file.nameWithoutExtension());
                }
            }
        });

        handler.register("gameover", "Force a game over.", arg -> {
            if(state.isMenu()){
                err("Not playing a map.");
                return;
            }

            info("Core destroyed.");
            inGameOverWait = false;
            Events.fire(new GameOverEvent(state.rules.waveTeam));
        });

        handler.register("info", "<IP/UUID/name...>", "Find player info(s). Can optionally check for all names or IPs a player has had.", arg -> {
            ObjectSet<PlayerInfo> infos = netServer.admins.findByName(arg[0]);

            if(infos.size > 0){
                info("Players found: @", infos.size);

                int i = 0;
                for(PlayerInfo info : infos){
                    info("[@] Trace info for player '@' / UUID @ / RAW @", i++, info.plainLastName(), info.id, info.lastName);
                    info("  all names used: @", info.names);
                    info("  IP: @", info.lastIP);
                    info("  all IPs used: @", info.ips);
                    info("  times joined: @", info.timesJoined);
                    info("  times kicked: @", info.timesKicked);
                }
            }else{
                info("Nobody with that name could be found.");
            }
        });

        handler.register("search", "<name...>", "Search players who have used part of a name.", arg -> {
            ObjectSet<PlayerInfo> infos = netServer.admins.searchNames(arg[0]);

            if(infos.size > 0){
                info("Players found: @", infos.size);

                int i = 0;
                for(PlayerInfo info : infos){
                    info("- [@] '@' / @", i++, info.plainLastName(), info.id);
                }
            }else{
                info("Nobody with that name could be found.");
            }
        });

        handler.register("gc", "Trigger a garbage collection. Testing only.", arg -> {
            int pre = (int)(Core.app.getJavaHeap() / 1024 / 1024);
            System.gc();
            int post = (int)(Core.app.getJavaHeap() / 1024 / 1024);
            info("@ MB collected. Memory usage now at @ MB.", pre - post, post);
        });

        handler.register("yes", "Run the last suggested incorrect command.", arg -> {
            if(suggested == null){
                err("There is nothing to say yes to.");
            }else{
                handleCommandString(suggested);
            }
        });

        handler.register("dos-ban", "[add/remove] [ip]", "Add or remove a DOS ban.", arg -> {
            if(arg.length == 0){
                info("DOS bans: @", netServer.admins.dosBlacklist.isEmpty() ? "<none>" : "");

                netServer.admins.dosBlacklist.forEach(address -> {
                    info("&lw\t" + address);
                });
                return;
            }else if(arg.length == 1){
                err("Expected either zero or two parameters, but only got one parameter.");
                return;
            }

            String action = arg[0].toLowerCase();
            String ip = arg[1];

            if(action.equals("add")){
                netServer.admins.blacklistDos(ip);
                info("Dos banned: @", ip);
                return;
            }else if(action.equals("remove")){
                netServer.admins.unBlacklistDos(ip);
                info("Removed dos ban: @", ip);
                return;
            }

            err("Unrecognized action: @", action);
        });

        mods.eachClass(p -> p.registerServerCommands(handler));
        YZFBridge.registerCommands(handler);
        installLocalizedCommandInfo();
    }

    public void handleCommandString(String line){
        CommandResponse response = handler.handleMessage(line);

        if(response.type == ResponseType.unknownCommand){

            int minDst = 0;
            Command closest = null;

            for(Command command : handler.getCommandList()){
                int dst = Strings.levenshtein(command.text, response.runCommand);
                if(dst < 3 && (closest == null || dst < minDst)){
                    minDst = dst;
                    closest = command;
                }
            }

            if(closest != null && !closest.text.equals("yes")){
                err("Command not found. Did you mean \"" + closest.text + "\"?");
                suggested = line.replace(response.runCommand, closest.text);
            }else{
                err("Invalid command. Type 'help' for help.");
            }
        }else if(response.type == ResponseType.fewArguments){
            err("Too few command arguments. Usage: " + response.command.text + " " + response.command.paramText);
        }else if(response.type == ResponseType.manyArguments){
            err("Too many command arguments. Usage: " + response.command.text + " " + response.command.paramText);
        }else if(response.type == ResponseType.valid){
            suggested = null;
        }
    }

    private void printServerHelpPage(int page){
        refreshLocalizedHelpSettings();
        Seq<Command> commands = handler.getCommandList();
        if(commands.isEmpty()){
            info("No commands registered.");
            return;
        }

        int pages = Math.max(1, (int)Math.ceil((double)commands.size / serverHelpPageSize));
        int resolvedPage = Math.min(Math.max(1, page), pages);
        int start = (resolvedPage - 1) * serverHelpPageSize;
        int end = Math.min(commands.size, start + serverHelpPageSize);

        info(helpLanguage == HelpLanguage.en ? "Commands [@/@]:" : "命令列表 [@/@]:", resolvedPage, pages);
        for(int i = start; i < end; i++){
            info(formatCommandLine(commands.get(i)));
        }

        if(resolvedPage < pages){
            info(helpLanguage == HelpLanguage.en
                ? "More commands: &lyhelp @&fr  /  List all: &lyhelp all&fr"
                : "更多命令: &lyhelp @&fr  /  全部列出: &lyhelp all&fr",
                resolvedPage + 1);
        }else{
            info(helpLanguage == HelpLanguage.en ? "List all: &lyhelp all&fr" : "全部列出: &lyhelp all&fr");
        }
    }

    private void printAllServerHelp(){
        refreshLocalizedHelpSettings();
        Seq<Command> commands = handler.getCommandList();
        if(commands.isEmpty()){
            info("No commands registered.");
            return;
        }

        info(helpLanguage == HelpLanguage.en ? "Commands [all]:" : "命令列表 [全部]:");
        for(Command command : commands){
            info(formatCommandLine(command));
        }
    }

    private void printServerHelpForCommand(Command command){
        refreshLocalizedHelpSettings();
        LocalizedCommandInfo meta = localizedCommandInfo.get(command.text.toLowerCase(Locale.ROOT));
        info("&b&lb@&fr:", command.text);
        info(helpLanguage == HelpLanguage.en ? "  Usage: @" : "  用法: @", buildUsageText(command));
        if(meta != null && !Strings.stripColors(meta.aliasLabel).isEmpty() && helpLanguage != HelpLanguage.en){
            info("  别名: @", meta.aliasLabel);
        }
        if(helpLanguage == HelpLanguage.en){
            info("  说明: @", command.description);
        }else if(helpLanguage == HelpLanguage.bilingual){
            info("  说明: @", meta != null && meta.descriptionZh != null && !meta.descriptionZh.isBlank() ? meta.descriptionZh : command.description);
            info("  EN: @", command.description);
        }else{
            info("  说明: @", meta != null && meta.descriptionZh != null && !meta.descriptionZh.isBlank() ? meta.descriptionZh : command.description);
        }
        if(meta != null && !meta.notes.isEmpty()){
            info(helpLanguage == HelpLanguage.en ? "  Notes:" : "  备注:");
            for(String note : meta.notes){
                info("    - @", note);
            }
        }
    }

    private Command findCommandByTextOrAlias(String input){
        if(input == null) return null;
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if(normalized.isEmpty()) return null;

        Command direct = handler.getCommandList().find(c -> c.text.equalsIgnoreCase(normalized));
        if(direct != null) return direct;

        for(Command command : handler.getCommandList()){
            LocalizedCommandInfo meta = localizedCommandInfo.get(command.text.toLowerCase(Locale.ROOT));
            if(meta != null){
                for(String alias : meta.aliases){
                    if(alias.equalsIgnoreCase(normalized)){
                        return command;
                    }
                }
            }
        }
        return null;
    }

    private String formatCommandLine(Command command){
        LocalizedCommandInfo meta = localizedCommandInfo.get(command.text.toLowerCase(Locale.ROOT));
        String aliasText = meta == null || meta.aliasLabel == null || meta.aliasLabel.isBlank() || helpLanguage == HelpLanguage.en ? "" : " &lk(&ly" + meta.aliasLabel + "&lk)";
        String description;
        if(helpLanguage == HelpLanguage.en){
            description = command.description;
        }else if(helpLanguage == HelpLanguage.bilingual){
            description = (meta == null || meta.descriptionZh == null || meta.descriptionZh.isBlank() ? command.description : meta.descriptionZh) + " &lk/ &lw" + command.description;
        }else{
            description = meta == null || meta.descriptionZh == null || meta.descriptionZh.isBlank() ? command.description : meta.descriptionZh;
        }

        return "  &b&lb" + command.text
            + (command.paramText.isEmpty() ? "" : " &lc&fi" + command.paramText)
            + "&fr"
            + aliasText
            + " - &lw" + description;
    }

    private String buildUsageText(Command command){
        LocalizedCommandInfo meta = localizedCommandInfo.get(command.text.toLowerCase(Locale.ROOT));
        String aliasText = meta == null || meta.aliasLabel == null || meta.aliasLabel.isBlank() || helpLanguage == HelpLanguage.en ? "" : " (" + meta.aliasLabel + ")";
        return command.text + (command.paramText.isEmpty() ? "" : " " + command.paramText) + aliasText;
    }

    private void refreshLocalizedHelpSettings(){
        serverHelpPageSize = serverHelpPageSizeDefault;
        helpLanguage = HelpLanguage.zh;

        try{
            Fi file = Vars.dataDirectory.child("yzf").child("config").child("terminal.hjson");
            if(!file.exists()) return;

            Jval root = Jval.read(YZFBridge.readTextSmart(file));
            if(root == null || !root.isObject()) return;

            int configuredPageSize = root.getInt("helpPageSize", root.getInt("pageSize", serverHelpPageSizeDefault));
            serverHelpPageSize = Math.max(1, configuredPageSize);

            String language = root.getString("helpLanguage", "zh").trim().toLowerCase(Locale.ROOT);
            if(language.equals("en") || language.equals("english")){
                helpLanguage = HelpLanguage.en;
            }else if(language.equals("both") || language.equals("bilingual") || language.equals("zh-en")){
                helpLanguage = HelpLanguage.bilingual;
            }else{
                helpLanguage = HelpLanguage.zh;
            }
        }catch(Exception ignored){
        }
    }

    private void installLocalizedCommandInfo(){
        localizedCommandInfo.clear();

        addLocalizedCommand("help", "帮助", "显示命令列表、分页列表，或查看指定命令的帮助。", "可用: `help`、`help 2`、`help all`、`help config`。");
        addLocalizedCommand("version", "版本", "显示服务端版本和 Java 版本信息。");
        addLocalizedCommand("exit", "退出", "退出整个服务端进程。");
        addLocalizedCommand("stop", "停服", "关闭当前正在托管的服务器，但不退出程序。");
        addLocalizedCommand("host", "开服", "开启服务器并加载地图。", "留空时会按当前规则选择随机地图。", "第二个参数是模式，例如 `survival`、`pvp`。");
        addLocalizedCommand("maps", "地图", "查看地图列表。", "`all` 显示全部地图。", "`custom` 只显示自定义地图。", "`default` 只显示内置地图。");
        addLocalizedCommand("reloadassets", "重载资源", "重新从磁盘加载服务端数据资源。");
        addLocalizedCommand("reloadmaps", "重载地图", "重新扫描并加载地图目录。");
        addLocalizedCommand("status", "状态", "显示当前服务器运行状态。");
        addLocalizedCommand("mods", "模组列表", "显示当前已加载模组。");
        addLocalizedCommand("mod", "模组信息", "查看指定模组的详细信息。");
        addLocalizedCommand("js", "脚本", "执行一段 JavaScript 控制台脚本。");
        addLocalizedCommand("say", "公告", "向所有在线玩家发送一条服务器消息。");
        addLocalizedCommand("pause", "暂停", "暂停或继续游戏。", "`on` 为暂停，`off` 为继续。");
        addLocalizedCommand("rules", "规则", "查看、添加或移除全局规则。", "`rules` 直接查看全部规则。", "`rules add 名称 值` 添加或修改规则。", "`rules remove 名称` 删除规则。");
        addLocalizedCommand("dumpsettings", "导出设置", "输出全部设置项，便于排查问题。");
        addLocalizedCommand("fillitems", "满仓", "将指定队伍核心填满物资。");
        addLocalizedCommand("playerlimit", "人数上限", "查看或设置玩家人数上限。", "`off` 关闭人数限制。");
        addLocalizedCommand("config", "配置", "查看或修改服务器配置项。", "不带参数时列出全部配置。", "`config 名称` 查看单项当前值。", "`config 名称 default` 恢复默认值。", "布尔值支持 `on/off` 或 `true/false`。");
        addLocalizedCommand("subnet-ban", "子网封禁", "管理子网封禁列表。", "`add` 添加封禁。", "`remove` 移除封禁。");
        addLocalizedCommand("name-ban", "名称封禁", "按正则表达式封禁名称。", "`clear` 清空全部名称封禁。");
        addLocalizedCommand("whitelist", "白名单", "管理白名单玩家。");
        addLocalizedCommand("shuffle", "地图轮换", "设置地图轮换模式。", "可选值: `none`、`all`、`custom`、`builtin`。");
        addLocalizedCommand("nextmap", "下张地图", "设置游戏结束后的下一张地图。");
        addLocalizedCommand("kick", "踢出", "按名称踢出在线玩家。");
        addLocalizedCommand("ban", "封禁", "按 ID、名称或 IP 封禁玩家。", "第一个参数可选: `id`、`name`、`ip`。");
        addLocalizedCommand("bans", "封禁列表", "显示当前所有 ID 和 IP 封禁。");
        addLocalizedCommand("unban", "解封", "按 IP 或 ID 完全解封玩家。");
        addLocalizedCommand("pardon", "赦免", "清除玩家的投票踢出限制。");
        addLocalizedCommand("admin", "管理员", "添加或移除管理员。", "第一个参数可选: `add`、`remove`。");
        addLocalizedCommand("admins", "管理员列表", "显示所有管理员。");
        addLocalizedCommand("players", "玩家", "列出当前在线玩家。");
        addLocalizedCommand("runwave", "下一波", "立刻触发下一波。");
        addLocalizedCommand("loadautosave", "读取自动存档", "读取最近一次自动存档。");
        addLocalizedCommand("load", "读取存档", "从指定槽位读取存档。");
        addLocalizedCommand("save", "保存存档", "保存到指定槽位。", "第二个参数 `embedAssets` 可选。");
        addLocalizedCommand("saves", "存档列表", "显示所有存档槽位。");
        addLocalizedCommand("gameover", "结束游戏", "强制触发本局结束。");
        addLocalizedCommand("info", "玩家信息", "查看玩家历史信息。", "可按 IP、UUID 或名称查询。");
        addLocalizedCommand("search", "搜索玩家", "按部分名称搜索历史玩家。");
        addLocalizedCommand("gc", "垃圾回收", "手动触发一次垃圾回收。");
        addLocalizedCommand("yes", "确认", "执行上一条纠错建议的命令。");
        addLocalizedCommand("dos-ban", "DOS封禁", "添加或移除 DOS 黑名单。");
        addLocalizedCommand("timedreload", "定时任务重载", "重载 timed task 插件配置。");
        addLocalizedCommand("uhdreload", "状态面板重载", "重载 UHD 状态插件配置。");
        addLocalizedCommand("jumpmine-transit-top", "跳板地雷中转排行", "查看中转目标排行榜。");
        addLocalizedCommand("jumpmine-status", "跳板地雷状态", "查看跳板地雷插件状态。");
        addLocalizedCommand("jumpmine-reload", "跳板地雷重载", "重载跳板地雷配置与绑定。");
        addLocalizedCommand("jumpmine-rescan", "跳板地雷重扫", "重新扫描当前地图中的地雷槽位。");
        addLocalizedCommand("jumpmine-sync", "跳板地雷同步", "立即同步远程状态并重绘标签。");
        addLocalizedCommand("yzf", "monthzifang", "MindustryYZF 服务端扩展命令入口。", "可继续使用 `yzf help` 查看下级子命令。");
    }

    private void addLocalizedCommand(String command, String aliasLabel, String descriptionZh, String... notes){
        LocalizedCommandInfo info = new LocalizedCommandInfo(aliasLabel, descriptionZh);
        if(aliasLabel != null){
            for(String part : aliasLabel.split("/")){
                String trimmed = part.trim();
                if(!trimmed.isEmpty()){
                    info.aliases.add(trimmed);
                }
            }
        }
        if(notes != null){
            for(String note : notes){
                if(note != null && !note.isBlank()){
                    info.notes.add(note);
                }
            }
        }
        localizedCommandInfo.put(command.toLowerCase(Locale.ROOT), info);
    }

    private static final class LocalizedCommandInfo{
        final String aliasLabel;
        final String descriptionZh;
        final Seq<String> aliases = new Seq<>();
        final Seq<String> notes = new Seq<>();

        LocalizedCommandInfo(String aliasLabel, String descriptionZh){
            this.aliasLabel = aliasLabel == null ? "" : aliasLabel;
            this.descriptionZh = descriptionZh == null ? "" : descriptionZh;
        }
    }

    private enum HelpLanguage{
        zh,
        en,
        bilingual
    }

    /**
     * Cancels the world load timer task, if it is scheduled. Can be useful for stopping a server or hosting a new game.
     */
    public void cancelPlayTask(){
        if(lastTask != null) lastTask.cancel();
    }

    /**
     * Resets the world state, starts a new game.
     * @param run What task to run to load a new world.
     */
    public void play(Runnable run){
        play(true, run);
    }

    /**
     * Resets the world state, starts a new game.
     * @param wait Whether to wait for {@link Config#roundExtraTime} seconds before starting a new game.
     * @param run What task to run to load a new world.
     */
    public void play(boolean wait, Runnable run){
        inGameOverWait = true;
        cancelPlayTask();

        Runnable reload = () -> {
            try{
                WorldReloader reloader = new WorldReloader();
                reloader.begin();

                run.run();

                state.rules = state.map.applyRules(lastMode);
                logic.play();

                reloader.end();
                inGameOverWait = false;
            }catch(MapException e){
                err("@: @", e.map.plainName(), e.getMessage());
                net.closeServer();
            }
        };

        if(wait){
            lastTask = Timer.schedule(reload, Config.roundExtraTime.num());
        }else{
            reload.run();
        }
    }

    public synchronized void logToFile(String text){
        String day = LocalDate.now().toString();
        if(!day.equals(currentLogDay)){
            currentLogDay = day;
            currentLogFile = null;
            pruneManagedLogs(logFolder, day);
        }
        if(currentLogFile != null && currentLogFile.length() >= managedLogMaxBytes){
            currentLogFile.writeString("[End of log file. Date: " + dateTime.format(LocalDateTime.now()) + "]\n", true);
            currentLogFile = null;
        }

        for(String value : values){
            text = text.replace(value, "");
        }

        if(currentLogFile == null){
            Fi normalDirectory = logFolder.child(currentLogDay).child("normal");
            normalDirectory.mkdirs();
            int i = 0;
            while(normalDirectory.child("normal-" + i + ".log").length() >= managedLogMaxBytes){
                i++;
            }
            currentLogFile = normalDirectory.child("normal-" + i + ".log");
        }

        currentLogFile.writeString(text + "\n", true);
    }

    private void pruneManagedLogs(Fi root, String currentDay){
        LocalDate cutoff = LocalDate.now().minusDays(14);
        for(Fi child : root.list()){
            if(!child.isDirectory() || currentDay.equals(child.name())) continue;
            try{
                if(LocalDate.parse(child.name()).isBefore(cutoff)) child.deleteDirectory();
            }catch(Exception ignored){
                // Only ISO date directories are managed by this retention policy.
            }
        }
    }

    public void toggleSocket(boolean on){
        if(on && socketThread == null){
            socketThread = new Thread(() -> {
                try{
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    InetAddress bindAddress = InetAddress.getByName(Config.socketInputAddress.string());
                    YZFBridge.ExternalAccess access = YZFBridge.externalAccess();
                    if(access != null && !access.allowsSocketBind(bindAddress)){
                        err("Refusing to bind command socket publicly without allowInsecurePublicSocket: true.");
                        return;
                    }
                    serverSocket.bind(new InetSocketAddress(bindAddress, Config.socketInputPort.num()));
                    while(!shuttingDown && !serverSocket.isClosed()){
                        Socket client = serverSocket.accept();
                        if(!socketClientSlots.tryAcquire()){
                            client.close();
                            Log.warn("Command socket connection rejected: client limit reached.");
                            continue;
                        }
                        info("&lkReceived command socket connection: &fi@", serverSocket.getLocalSocketAddress());
                        Thread clientThread = new Thread(() -> handleSocketClient(client), "Server Command Socket Client");
                        clientThread.setDaemon(true);
                        clientThread.start();
                    }
                }catch(BindException b){
                    err("Command input socket already in use. Is another instance of the server running?");
                }catch(IOException e){
                    String message = e.getMessage();
                    if(!shuttingDown && !"Socket closed".equals(message) && !"Connection reset".equals(message)){
                        err("Terminating socket server.");
                        err(e);
                    }
                }finally{
                    socketThread = null;
                }
            });
            socketThread.setDaemon(true);
            socketThread.start();
        }else if(socketThread != null){
            socketThread.interrupt();
            try{
                if(serverSocket != null) serverSocket.close();
            }catch(IOException e){
                err(e);
            }
            for(Socket client : socketClients){
                try{
                    client.close();
                }catch(IOException ignored){
                }
            }
            socketClients.clear();
            socketOutputs.clear();
            socketThread = null;
        }
    }

    private void handleSocketClient(Socket client){
        socketClients.add(client);
        try(Socket socket = client;
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)){
            socketOutputs.add(output);
            socket.setSoTimeout(socketAuthenticationTimeoutMillis);
            YZFBridge.ExternalAccess externalAccess = YZFBridge.externalAccess();
            if(externalAccess == null || !externalAccess.allows(socket.getInetAddress(), readSocketToken(input, socket.getInetAddress()))){
                output.println("ERR authentication required");
                return;
            }
            socket.setSoTimeout(socketIdleTimeoutMillis);
            String line;
            long commandWindow = System.currentTimeMillis();
            int commandCount = 0;
            while(!shuttingDown && (line = readSocketLine(input)) != null){
                long now = System.currentTimeMillis();
                if(now - commandWindow >= 60_000L){
                    commandWindow = now;
                    commandCount = 0;
                }
                if(++commandCount > socketMaxCommandsPerMinute){
                    output.println("ERR command rate limit exceeded");
                    return;
                }
                String result = line;
                Core.app.post(() -> handleCommandString(result));
            }
        }catch(IOException e){
            if(!shuttingDown && !"Connection reset".equals(e.getMessage())){
                Log.warn("Command socket client disconnected: @", Strings.getSimpleMessage(e));
            }
        }finally{
            socketClients.remove(client);
            socketOutputs.removeIf(PrintWriter::checkError);
            socketClientSlots.release();
            info("&lkLost command socket connection.");
        }
    }

    private String readSocketToken(BufferedReader input, InetAddress address) throws IOException{
        YZFBridge.ExternalAccess externalAccess = YZFBridge.externalAccess();
        if(externalAccess != null && !externalAccess.requiresToken(address)) return "";
        String line = readSocketLine(input);
        return line != null && line.startsWith("AUTH ") ? line.substring(5).trim() : "";
    }

    private String readSocketLine(BufferedReader input) throws IOException{
        StringBuilder line = new StringBuilder();
        int character;
        while((character = input.read()) != -1){
            if(character == '\n') return line.toString();
            if(character != '\r') line.append((char)character);
            if(line.length() > socketMaxLineChars) throw new IOException("Command socket line exceeds 16 KiB limit");
        }
        return line.isEmpty() ? null : line.toString();
    }

    @Override
    public void dispose(){
        shuttingDown = true;
        cancelPlayTask();
        toggleSocket(false);
        YZFBridge.shutdown();

        try{
            if(lineReader != null){
                lineReader.getTerminal().close();
            }
        }catch(IOException ignored){
        }

        try{
            if(simpleConsoleReader != null){
                simpleConsoleReader.close();
            }
        }catch(IOException ignored){
        }
    }
}
