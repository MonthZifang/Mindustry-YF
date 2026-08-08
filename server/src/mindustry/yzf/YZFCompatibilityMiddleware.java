package mindustry.yzf;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import rhino.Context;
import rhino.Scriptable;

import java.util.Arrays;

public final class YZFCompatibilityMiddleware{
    private final Fi directory;

    public YZFCompatibilityMiddleware(Fi directory){
        this.directory = directory;
    }

    public void apply(Context ctx, Scriptable scope, YZFModuleDefinition module){
        ctx.evaluateString(scope, builtinSource(), module.mainScript.absolutePath() + "#yzf-compat-builtin", 1);

        for(Fi file : middlewareFiles()){
            try{
                ctx.evaluateString(scope, YZFText.readTextSmart(file), file.absolutePath(), 1);
            }catch(Throwable t){
                MindustryYZF.context().metrics.moduleFailures.incrementAndGet();
                MindustryYZF.context().metrics.markFailure("compat-middleware:" + file.name() + ": " + t.getMessage());
                Log.err("[@] Compatibility middleware failed: @", MindustryYZF.name, file.absolutePath(), t);
                throw t;
            }
        }
    }

    private Seq<Fi> middlewareFiles(){
        Seq<Fi> files = new Seq<>();
        if(directory == null || !directory.exists()) return files;
        collect(directory, files);
        files.sort((a, b) -> a.absolutePath().replace('\\', '/').compareToIgnoreCase(b.absolutePath().replace('\\', '/')));
        return files;
    }

    private void collect(Fi root, Seq<Fi> out){
        if(root == null || !root.exists()) return;
        if(root.isDirectory()){
            Fi[] children = root.list();
            Arrays.sort(children, (a, b) -> a.name().compareToIgnoreCase(b.name()));
            for(Fi child : children){
                collect(child, out);
            }
            return;
        }
        if(root.extension().equalsIgnoreCase("js")){
            out.add(root);
        }
    }

    private String builtinSource(){
        return """
            (function(global){
              function define(name, value){
                if(global[name] === undefined || global[name] === null){
                  global[name] = value;
                }
                return global[name];
              }

              define('Core', Packages.arc.Core);
              define('Events', Packages.arc.Events);
              define('Timer', Packages.arc.util.Timer);
              define('Log', Packages.arc.util.Log);
              define('Vars', Packages.mindustry.Vars);
              define('Call', Packages.mindustry.gen.Call);
              define('Groups', Packages.mindustry.gen.Groups);
              define('Blocks', Packages.mindustry.content.Blocks);
              define('Items', Packages.mindustry.content.Items);
              define('Liquids', Packages.mindustry.content.Liquids);
              define('UnitTypes', Packages.mindustry.content.UnitTypes);
              define('StatusEffects', Packages.mindustry.content.StatusEffects);
              define('Fx', Packages.mindustry.content.Fx);
              define('Pal', Packages.mindustry.graphics.Pal);

              var compat = global.yzfCompat || {};
              compat.version = '159.7';
              compat.define = define;
              compat.getPath = function(root, path){
                var parts = String(path).split('.');
                var value = root;
                for(var i = 0; i < parts.length; i++){
                  if(value == null) return null;
                  value = value[parts[i]];
                }
                return value;
              };
              compat.setPath = function(root, path, value){
                var parts = String(path).split('.');
                var target = root;
                for(var i = 0; i < parts.length - 1; i++){
                  var key = parts[i];
                  if(target[key] == null) target[key] = {};
                  target = target[key];
                }
                target[parts[parts.length - 1]] = value;
                return value;
              };
              compat.alias = function(oldName, value){
                return define(String(oldName), value);
              };
              compat.aliasYzf = function(oldPath, value){
                return compat.setPath(global.yzf, oldPath, value);
              };
              compat.aliasPath = function(oldPath, newPath){
                return define(String(oldPath), compat.getPath(global, newPath));
              };
              compat.aliasPackage = function(oldName, packagePath){
                var parts = String(packagePath).split('.');
                var value = Packages;
                var start = parts[0] === 'Packages' ? 1 : 0;
                for(var i = start; i < parts.length; i++){
                  value = value == null ? null : value[parts[i]];
                }
                return define(String(oldName), value);
              };
              compat.eventAliases = compat.eventAliases || {};
              compat.aliasEvent = function(oldName, newName){
                compat.eventAliases[String(oldName)] = String(newName);
              };
              if(global.yzf && typeof global.yzf.on === 'function' && !global.yzf.__compatOnWrapped){
                var originalOn = global.yzf.on;
                global.yzf.on = function(eventName, fn){
                  var mapped = compat.eventAliases[String(eventName)] || String(eventName);
                  return originalOn(mapped, fn);
                };
                global.yzf.__compatOnWrapped = true;
              };
              compat.install = function(fn){
                if(typeof fn === 'function'){
                  return fn(global.yzf, global.yzfModule, compat, global);
                }
                return null;
              };
              global.yzfCompat = compat;
            })(this);
            """;
    }
}
