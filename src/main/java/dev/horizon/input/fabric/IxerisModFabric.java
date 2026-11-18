package dev.horizon.input.fabric;

//? if fabric {
import dev.horizon.input.IxerisMod;
import dev.horizon.input.JavaHelper;
import net.fabricmc.api.ClientModInitializer;

public class IxerisModFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (JavaHelper.JAVA_SUPPORTED) {
            IxerisMod.init();
        }
    }
}
//?}
