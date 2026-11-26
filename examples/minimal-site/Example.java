package app.filmmerkezi.addon;

import app.filmmerkezi.tv.pluginapi.Plugin;
import app.filmmerkezi.tv.pluginapi.PluginHost;
import app.filmmerkezi.tv.pluginapi.PluginException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Compile against pluginApi.jar and Android API 23+, without host implementation imports. */
public final class Example implements Plugin {
    @Override public String call(String operation, String payload, PluginHost host) throws Exception {
        if (host.cancelled()) throw new InterruptedException("Cancelled");
        if (operation.equals("describe")) return new JSONObject().put("sdk", 1).toString();
        if (operation.equals("search") || operation.equals("browse")) {
            // Replace this empty sample with your own HTTP/parser and schema-valid cards.
            return new JSONObject().put("cards", new JSONArray()).put("next", JSONObject.NULL).toString();
        }
        throw new PluginException("UNSUPPORTED", "Example operation is not implemented", "example", 0);
    }
}
