package mindustry.yzf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class YZFRegionProcessMain{
    private YZFRegionProcessMain(){}
    public static void main(String[] args) throws Exception{
        String id = args.length == 0 ? "YF2" : args[0];
        System.out.println("[YZF-REGION] started " + id);
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))){
            String line;
            while((line = reader.readLine()) != null){
                if("shutdown".equalsIgnoreCase(line.trim())) break;
                if("status".equalsIgnoreCase(line.trim())) System.out.println("{\"id\":\"" + id + "\",\"state\":\"active\"}");
            }
        }
    }
}
