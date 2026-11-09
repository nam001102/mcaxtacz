package com.example.mcaxtacz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.logging.LogUtils;

import net.minecraftforge.fml.common.Mod;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(mcaxtacz.MODID)
public class mcaxtacz {
    public static final String MODID = "mcaxtacz";
    public static final Logger LOGGER = LoggerFactory.getLogger(mcaxtacz.class);
    public mcaxtacz() {
            LOGGER.info("MCA <-> TaCZ gun compat loaded.");
    }

}
