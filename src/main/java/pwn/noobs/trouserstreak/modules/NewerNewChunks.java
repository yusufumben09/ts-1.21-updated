package pwn.noobs.trouserstreak.modules;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.play.AcknowledgeChunksC2SPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import pwn.noobs.trouserstreak.Trouser;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/*
    Ported from: https://github.com/BleachDrinker420/BleachHack/blob/master/BleachHack-Fabric-1.16/src/main/java/bleach/hack/module/mods/NewChunks.java
    updated by etianll :D
*/
public class NewerNewChunks extends Module {
	public enum DetectMode {
		Normal,
		IgnoreBlockExploit,
		BlockExploitMode
	}
	private final SettingGroup specialGroup = settings.createGroup("Disable Pre 1.17 Chunk Detector if server version <1.17");
	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgCdata = settings.createGroup("Saved Chunk Data");
	private final SettingGroup sgcacheCdata = settings.createGroup("Cached Chunk Data");
	private final SettingGroup sgRender = settings.createGroup("Render");

	private final Setting<Boolean> oldchunksdetector = specialGroup.add(new BoolSetting.Builder()
			.name("Pre 1.17 OldChunk Detector")
			.description("Marks chunks as old if they have no copper above Y 0 and are in the overworld. For detecting oldchunks in servers updated from old version.")
			.defaultValue(false)
			.build()
	);
	public final Setting<DetectMode> detectmode = sgGeneral.add(new EnumSetting.Builder<DetectMode>()
			.name("Chunk Detection Mode")
			.description("Anything other than normal is for old servers where build limits are being increased due to updates.")
			.defaultValue(DetectMode.Normal)
			.build()
	);
	private final Setting<Boolean> byteexploit = sgGeneral.add(new BoolSetting.Builder()
			.name("ByteExploit")
			.description("Detects newchunks based on byte sizes of the chunk sections.")
			.defaultValue(true)
			.build()
	);
	private final Setting<Boolean> liquidexploit = sgGeneral.add(new BoolSetting.Builder()
			.name("LiquidExploit")
			.description("Estimates newchunks based on flowing liquids.")
			.defaultValue(false)
			.build()
	);
	private final Setting<Boolean> blockupdateexploit = sgGeneral.add(new BoolSetting.Builder()
			.name("BlockUpdateExploit")
			.description("Estimates newchunks based on block updates. THESE MAY POSSIBLY BE OLD. BlockExploitMode needed to help determine false positives.")
			.defaultValue(false)
			.build()
	);
	private final Setting<Boolean> remove = sgcacheCdata.add(new BoolSetting.Builder()
			.name("RemoveOnModuleDisabled")
			.description("Removes the cached chunks when disabling the module.")
			.defaultValue(true)
			.build()
	);
	private final Setting<Boolean> worldleaveremove = sgcacheCdata.add(new BoolSetting.Builder()
			.name("RemoveOnLeaveWorldOrChangeDimensions")
			.description("Removes the cached chunks when leaving the world or changing dimensions.")
			.defaultValue(true)
			.build()
	);
	private final Setting<Boolean> save = sgCdata.add(new BoolSetting.Builder()
			.name("SaveChunkData")
			.description("Saves the cached chunks to a file.")
			.defaultValue(true)
			.build()
	);
	private final Setting<Boolean> load = sgCdata.add(new BoolSetting.Builder()
			.name("LoadChunkData")
			.description("Loads the saved chunks from the file.")
			.defaultValue(true)
			.build()
	);
	private final Setting<Boolean> autoreload = sgCdata.add(new BoolSetting.Builder()
			.name("AutoReloadChunks")
			.description("Reloads the chunks automatically from your savefiles on a delay.")
			.defaultValue(false)
			.visible(() -> load.get())
			.build()
	);
	private final Setting<Integer> removedelay = sgCdata.add(new IntSetting.Builder()
			.name("AutoReloadDelayInSeconds")
			.description("Reloads the chunks automatically from your savefiles on a delay.")
			.sliderRange(1,300)
			.defaultValue(60)
			.visible(() -> autoreload.get() && load.get())
			.build()
	);

	@Override
	public WWidget getWidget(GuiTheme theme) {
		WTable table = theme.table();
		WButton deletedata = table.add(theme.button("**DELETE CHUNK DATA**")).expandX().minWidth(100).widget();
		deletedata.action = () -> {
			if (deletewarning==0) error("PRESS AGAIN WITHIN 5s TO DELETE ALL CHUNK DATA FOR THIS DIMENSION.");
			deletewarningTicks=0;
			deletewarning++;
		};
		table.row();
		return table;
	}

	// render
	public final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
			.name("Render-Distance(Chunks)")
			.description("How many chunks from the character to render the detected chunks.")
			.defaultValue(128)
			.min(6)
			.sliderRange(6,1024)
			.build()
	);
	public final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
			.name("render-height")
			.description("The height at which new chunks will be rendered")
			.defaultValue(0)
			.min(-64)
			.sliderRange(-64,319)
			.build()
	);

	private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
			.name("shape-mode")
			.description("How the shapes are rendered.")
			.defaultValue(ShapeMode.Both)
			.build()
	);

	private final Setting<SettingColor> newChunksSideColor = sgRender.add(new ColorSetting.Builder()
			.name("new-chunks-side-color")
			.description("Color of the chunks that are (most likely) completely new.")
			.defaultValue(new SettingColor(255, 0, 0, 95))
			.visible(() -> (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both))
			.build()
	);
	private final Setting<SettingColor> tickexploitChunksSideColor = sgRender.add(new ColorSetting.Builder()
			.name("BlockExploitChunks-side-color")
			.description("MAY POSSIBLY BE OLD. Color of the chunks that have been triggered via block ticking packets")
			.defaultValue(new SettingColor(0, 0, 255, 75))
			.visible(() -> (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both) && detectmode.get()== DetectMode.BlockExploitMode && blockupdateexploit.get())
			.build()
	);

	private final Setting<SettingColor> oldChunksSideColor = sgRender.add(new ColorSetting.Builder()
			.name("old-chunks-side-color")
			.description("Color of the chunks that have (most likely) been loaded before.")
			.defaultValue(new SettingColor(0, 255, 0, 40))
			.visible(() -> shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both)
			.build()
	);

	private final Setting<SettingColor> newChunksLineColor = sgRender.add(new ColorSetting.Builder()
			.name("new-chunks-line-color")
			.description("Color of the chunks that are (most likely) completely new.")
			.defaultValue(new SettingColor(255, 0, 0, 205))
			.visible(() -> (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both))
			.build()
	);
	private final Setting<SettingColor> tickexploitChunksLineColor = sgRender.add(new ColorSetting.Builder()
			.name("BlockExploitChunks-line-color")
			.description("MAY POSSIBLY BE OLD. Color of the chunks that have been triggered via block ticking packets")
			.defaultValue(new SettingColor(0, 0, 255, 170))
			.visible(() -> (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both) && detectmode.get()== DetectMode.BlockExploitMode && blockupdateexploit.get())
			.build()
	);

	private final Setting<SettingColor> oldChunksLineColor = sgRender.add(new ColorSetting.Builder()
			.name("old-chunks-line-color")
			.description("Color of the chunks that have (most likely) been loaded before.")
			.defaultValue(new SettingColor(0, 255, 0, 100))
			.visible(() -> shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both)
			.build()
	);
	private final Executor taskExecutor = Executors.newSingleThreadExecutor();
	private int deletewarningTicks=666;
	private int deletewarning=0;
	private String serverip;
	private String world;
	private ChunkPos chunkPos;
	private ChunkPos oldpos;
	private boolean isNewGeneration;
	private boolean foundAnyOre;

	private final Set<ChunkPos> newChunks = Collections.synchronizedSet(new HashSet<>());
	private final Set<ChunkPos> oldChunks = Collections.synchronizedSet(new HashSet<>());
	private final Set<ChunkPos> tickexploitChunks = Collections.synchronizedSet(new HashSet<>());
	private static final Direction[] searchDirs = new Direction[] { Direction.EAST, Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.UP };
	private int errticks=0;
	private int autoreloadticks=0;
	private int loadingticks=0;
	private int reloadworld=0;
	public int chunkcounterticks=0;
	public static boolean chunkcounter;
	public static int newchunksfound=0;
	public static int oldchunksfound=0;
	public static int tickexploitchunksfound=0;
	public NewerNewChunks() {
		super(Trouser.Main,"NewerNewChunks", "Detects new chunks by using the byte sizes of chunk sections. Can also check liquid flow, and block ticking packets.");
	}
	@Override
	public void onActivate() {
		if (autoreload.get()) {
			newChunks.clear();
			oldChunks.clear();
			tickexploitChunks.clear();
		}
		if (mc.isInSingleplayer()==true){
			String[] array = mc.getServer().getSavePath(WorldSavePath.ROOT).toString().replace(':', '_').split("/|\\\\");
			serverip=array[array.length-2];
			world= mc.world.getRegistryKey().getValue().toString().replace(':', '_');
		} else {
			serverip = mc.getCurrentServerEntry().address.replace(':', '_');}
		world= mc.world.getRegistryKey().getValue().toString().replace(':', '_');
		if (save.get()){
			new File("TrouserStreak/NewChunks/"+serverip+"/"+world).mkdirs();
		}
		if (load.get()){
			loadData();
		}
		autoreloadticks=0;
		loadingticks=0;
		reloadworld=0;
	}

	@Override
	public void onDeactivate() {
		chunkcounterticks=0;
		autoreloadticks=0;
		loadingticks=0;
		reloadworld=0;
		if (remove.get()|autoreload.get()) {
			newChunks.clear();
			oldChunks.clear();
			tickexploitChunks.clear();
		}
		super.onDeactivate();
	}
	@EventHandler
	private void onScreenOpen(OpenScreenEvent event) {
		if (event.screen instanceof DisconnectedScreen) {
			chunkcounterticks=0;
			newchunksfound=0;
			oldchunksfound=0;
			tickexploitchunksfound=0;
			if (worldleaveremove.get()) {
				newChunks.clear();
				oldChunks.clear();
				tickexploitChunks.clear();
			}
		}
		if (event.screen instanceof DownloadingTerrainScreen) {
			chunkcounterticks=0;
			newchunksfound=0;
			oldchunksfound=0;
			tickexploitchunksfound=0;
			reloadworld=0;
		}
	}
	@EventHandler
	private void onGameLeft(GameLeftEvent event) {
		chunkcounterticks=0;
		newchunksfound=0;
		oldchunksfound=0;
		tickexploitchunksfound=0;
		if (worldleaveremove.get()) {
			newChunks.clear();
			oldChunks.clear();
			tickexploitChunks.clear();
		}
	}

	@EventHandler
	private void onPreTick(TickEvent.Pre event) {
		if (mc.player.getHealth()==0) {
			chunkcounterticks=0;
			newchunksfound=0;
			oldchunksfound=0;
			tickexploitchunksfound=0;
			reloadworld=0;
		}
		if (deletewarningTicks<=100) deletewarningTicks++;
		else deletewarning=0;
		if (deletewarning>=2){
			newChunks.clear();
			oldChunks.clear();
			tickexploitChunks.clear();
			new File("TrouserStreak/NewChunks/"+serverip+"/"+world+"/NewChunkData.txt").delete();
			new File("TrouserStreak/NewChunks/"+serverip+"/"+world+"/OldChunkData.txt").delete();
			new File("TrouserStreak/NewChunks/"+serverip+"/"+world+"/BlockExploitChunkData.txt").delete();
			error("Chunk Data deleted for this Dimension.");
			deletewarning=0;
		}
		if (detectmode.get()== DetectMode.Normal && blockupdateexploit.get()){
			if (errticks<6){
				errticks++;}
			if (errticks==5){
				error("BlockExploitMode RECOMMENDED. Required to determine false positives from the Block Exploit from the OldChunks.");
			}
		} else errticks=0;
		if (load.get()){
			loadingticks++;
			if (loadingticks<2){
				loadData();
			}
		} else if (!load.get()){
			loadingticks=0;
		}
		if (!Files.exists(Paths.get("TrouserStreak/NewChunks/"+serverip+"/"+world+"/OldChunkData.txt"))){
			File file = new File("TrouserStreak/NewChunks/"+serverip+"/"+world+"/OldChunkData.txt");
			try {
				file.createNewFile();
			} catch (IOException e) {}
		}
		if (!Files.exists(Paths.get("TrouserStreak/NewChunks/"+serverip+"/"+world+"/NewChunkData.txt"))){
			File file = new File("TrouserStreak/NewChunks/"+serverip+"/"+world+"/NewChunkData.txt");
			try {
				file.createNewFile();
			} catch (IOException e) {}
		}
		if (!Files.exists(Paths.get("TrouserStreak/NewChunks/"+serverip+"/"+world+"/BlockExploitChunkData.txt"))){
			File file = new File("TrouserStreak/NewChunks/"+serverip+"/"+world+"/BlockExploitChunkData.txt");
			try {
				file.createNewFile();
			} catch (IOException e) {}
		}
		if (chunkcounter=true){
			chunkcounterticks++;
			if (chunkcounterticks>=1){
				chunkcounterticks=0;
				newchunksfound=0;
				oldchunksfound=0;
				tickexploitchunksfound=0;
				chunkcounter=false;}
			if (chunkcounter=true && chunkcounterticks<1){
				try {
					List<String> allLines = Files.readAllLines(Paths.get("TrouserStreak/NewChunks/"+serverip+"/"+world+"/OldChunkData.txt"));

					for (String line : allLines) {
						oldchunksfound++;
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
				try {
					List<String> allLines = Files.readAllLines(Paths.get("TrouserStreak/NewChunks/"+serverip+"/"+world+"/NewChunkData.txt"));

					for (String line : allLines) {
						newchunksfound++;
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
				try {
					List<String> allLines = Files.readAllLines(Paths.get("TrouserStreak/NewChunks/"+serverip+"/"+world+"/BlockExploitChunkData.txt"));

					for (String line : allLines) {
						tickexploitchunksfound++;
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		if (mc.isInSingleplayer()==true){
			String[] array = mc.getServer().getSavePath(WorldSavePath.ROOT).toString().replace(':', '_').split("/|\\\\");
			serverip=array[array.length-2];
			world= mc.world.getRegistryKey().getValue().toString().replace(':', '_');
		} else {
			serverip = mc.getCurrentServerEntry().address.replace(':', '_');}
		world= mc.world.getRegistryKey().getValue().toString().replace(':', '_');

		if (autoreload.get()) {
			autoreloadticks++;
			if (autoreloadticks==removedelay.get()*20){
				newChunks.clear();
				oldChunks.clear();
				tickexploitChunks.clear();
				if (load.get()){
					loadData();
				}
			} else if (autoreloadticks>=removedelay.get()*20){
				autoreloadticks=0;
			}
		}
		//autoreload when entering different dimensions
		if (reloadworld<10){
			reloadworld++;
		}
		if (reloadworld==5){
			if (worldleaveremove.get()){
				newChunks.clear();
				oldChunks.clear();
				tickexploitChunks.clear();
			}
			loadData();
		}
	}
	@EventHandler
	private void onRender(Render3DEvent event) {
		if (newChunksLineColor.get().a > 5 || newChunksSideColor.get().a > 5) {
			synchronized (newChunks) {
				for (ChunkPos c : newChunks) {
					if (c != null && mc.getCameraEntity().getBlockPos().isWithinDistance(c.getStartPos(), renderDistance.get()*16)) {
						render(new Box(new Vec3d(c.getStartPos().getX(), c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()), new Vec3d(c.getStartPos().getX()+16, c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()+16)), newChunksSideColor.get(), newChunksLineColor.get(), shapeMode.get(), event);
					}
				}
			}
		}
		if (tickexploitChunksLineColor.get().a > 5 || tickexploitChunksSideColor.get().a > 5) {
			synchronized (tickexploitChunks) {
				for (ChunkPos c : tickexploitChunks) {
					if (c != null && mc.getCameraEntity().getBlockPos().isWithinDistance(c.getStartPos(), renderDistance.get()*16)) {
						if (detectmode.get()== DetectMode.BlockExploitMode && blockupdateexploit.get()) {
							render(new Box(new Vec3d(c.getStartPos().getX(), c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()), new Vec3d(c.getStartPos().getX()+16, c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()+16)), tickexploitChunksSideColor.get(), tickexploitChunksLineColor.get(), shapeMode.get(), event);
						} else if ((detectmode.get()== DetectMode.Normal) && blockupdateexploit.get()) {
							render(new Box(new Vec3d(c.getStartPos().getX(), c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()), new Vec3d(c.getStartPos().getX()+16, c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()+16)), newChunksSideColor.get(), newChunksLineColor.get(), shapeMode.get(), event);
						} else if ((detectmode.get()== DetectMode.IgnoreBlockExploit) && blockupdateexploit.get()) {
							render(new Box(new Vec3d(c.getStartPos().getX(), c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()), new Vec3d(c.getStartPos().getX()+16, c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()+16)), oldChunksSideColor.get(), oldChunksLineColor.get(), shapeMode.get(), event);
						} else if ((detectmode.get()== DetectMode.BlockExploitMode | detectmode.get()== DetectMode.Normal | detectmode.get()== DetectMode.IgnoreBlockExploit) && !blockupdateexploit.get()) {
							render(new Box(new Vec3d(c.getStartPos().getX(), c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()), new Vec3d(c.getStartPos().getX()+16, c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()+16)), oldChunksSideColor.get(), oldChunksLineColor.get(), shapeMode.get(), event);
						}
					}
				}
			}
		}
		if (oldChunksLineColor.get().a > 5 || oldChunksSideColor.get().a > 5){
			synchronized (oldChunks) {
				for (ChunkPos c : oldChunks) {
					if (c != null && mc.getCameraEntity().getBlockPos().isWithinDistance(c.getStartPos(), renderDistance.get()*16)) {
						render(new Box(new Vec3d(c.getStartPos().getX(), c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()), new Vec3d(c.getStartPos().getX()+16, c.getStartPos().getY()+renderHeight.get(), c.getStartPos().getZ()+16)), oldChunksSideColor.get(), oldChunksLineColor.get(), shapeMode.get(), event);
					}
				}
			}
		}
	}

	private void render(Box box, Color sides, Color lines, ShapeMode shapeMode, Render3DEvent event) {
		event.renderer.box(
				box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, sides, lines, shapeMode, 0);
	}

	@EventHandler
	private void onReadPacket(PacketEvent.Receive event) {
		if (event.packet instanceof AcknowledgeChunksC2SPacket)return;
		if (event.packet instanceof ChunkDeltaUpdateS2CPacket && liquidexploit.get()) {
			ChunkDeltaUpdateS2CPacket packet = (ChunkDeltaUpdateS2CPacket) event.packet;

			packet.visitUpdates((pos, state) -> {
				chunkPos = new ChunkPos(pos);
				if (!state.getFluidState().isEmpty() && !state.getFluidState().isStill()) {
					for (Direction dir: searchDirs) {
						if (mc.world.getBlockState(pos.offset(dir)).getFluidState().isStill() && (!newChunks.contains(chunkPos) && !oldChunks.contains(chunkPos))) {
							if (tickexploitChunks.contains(chunkPos)) tickexploitChunks.remove(chunkPos);
							newChunks.add(chunkPos);
							if (save.get()){
								saveNewChunkData(chunkPos);
							}
							return;
						}
					}
				}
			});
		}
		else if (!(event.packet instanceof AcknowledgeChunksC2SPacket) && event.packet instanceof BlockUpdateS2CPacket) {
			BlockUpdateS2CPacket packet = (BlockUpdateS2CPacket) event.packet;
			chunkPos = new ChunkPos(packet.getPos());
			if (blockupdateexploit.get()){
				try {
					if (!tickexploitChunks.contains(chunkPos) && !oldChunks.contains(chunkPos) && !newChunks.contains(chunkPos)){
						tickexploitChunks.add(chunkPos);
						if (save.get()){
							saveBlockExploitChunkData(chunkPos);
						}
					}
				}
				catch (Exception e){
					e.printStackTrace();
				}
			}
			if (!packet.getState().getFluidState().isEmpty() && !packet.getState().getFluidState().isStill() && liquidexploit.get()) {
				for (Direction dir: searchDirs) {
					if (mc.world.getBlockState(packet.getPos().offset(dir)).getFluidState().isStill() && (!newChunks.contains(chunkPos) && !oldChunks.contains(chunkPos))) {
						if (tickexploitChunks.contains(chunkPos)) tickexploitChunks.remove(chunkPos);
						newChunks.add(chunkPos);
						if (save.get()){
							saveNewChunkData(chunkPos);
						}
						return;
					}
				}
			}
		}
		else if (event.packet instanceof ChunkDataS2CPacket && mc.world != null) {
			ChunkDataS2CPacket packet = (ChunkDataS2CPacket) event.packet;
			oldpos = new ChunkPos(packet.getChunkX(), packet.getChunkZ());

			if (mc.world.getChunkManager().getChunk(packet.getChunkX(), packet.getChunkZ()) == null) {
				WorldChunk chunk = new WorldChunk(mc.world, oldpos);
				try {
					taskExecutor.execute(() -> chunk.loadFromPacket(packet.getChunkData().getSectionsDataBuf(), new NbtCompound(), packet.getChunkData().getBlockEntities(packet.getChunkX(), packet.getChunkZ())));
				} catch (ArrayIndexOutOfBoundsException e) {
					return;
				}
				PacketByteBuf buf = packet.getChunkData().getSectionsDataBuf();
				int widx = buf.writerIndex();
				if ((mc.world.getRegistryKey() == World.OVERWORLD || mc.world.getRegistryKey() == World.NETHER || mc.world.getRegistryKey() == World.END) && byteexploit.get()) {
					int initialReaderIndex = buf.readerIndex();
					int TOTAL_SECTIONS = 24;
					final int SECTION_HEIGHT = 16;
					int LOWEST_Y = -64;
					int TARGET_Y = 304;
					if (mc.world.getRegistryKey() == World.END){
						TOTAL_SECTIONS = 16;
						LOWEST_Y = 0;
						TARGET_Y = 0;
					}
					final int TARGET_SECTION = (TARGET_Y - LOWEST_Y) / SECTION_HEIGHT;

					int upperSectionsSize = 0;
					boolean errorOccurred = false;

					try {
						buf.skipBytes(24);

						for (int i = 0; i < TOTAL_SECTIONS; i++) {
							if (buf.readableBytes() < 1) {
								break; // Not enough data left, exit the loop
							}
							int sectionSize = readSafeVarInt(buf);
							if (sectionSize == -1) {
								break; // VarInt reading failed, exit the loop
							}

							if (i >= TARGET_SECTION) {
								upperSectionsSize += sectionSize;
							}

							if (buf.readableBytes() < sectionSize) {
								break; // Not enough data left, exit the loop
							}
							buf.skipBytes(sectionSize);
						}
					} catch (Exception e) {
						errorOccurred = true;
						//System.out.println("Error occurred: " + e.getMessage());
					} finally {
						buf.readerIndex(initialReaderIndex); // Reset the reader index
					}
					//System.out.println(upperSectionsSize);
					if (!errorOccurred && ((((widx >=128 && widx <288) || (upperSectionsSize != 57 && upperSectionsSize != 3)) && mc.world.getRegistryKey() == World.END) || (upperSectionsSize == 0 && mc.world.getRegistryKey() == World.NETHER) || (upperSectionsSize < 34 && mc.world.getRegistryKey() == World.OVERWORLD))) {
						try {
							if (!tickexploitChunks.contains(oldpos) && !oldChunks.contains(oldpos) && !newChunks.contains(oldpos)) {
								oldChunks.add(oldpos);
								if (save.get()) {
									saveOldChunkData(oldpos);
								}
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					} else if (!errorOccurred && (((widx == 288 || upperSectionsSize == 57 || upperSectionsSize == 3) && mc.world.getRegistryKey() == World.END) || (upperSectionsSize > 0 && mc.world.getRegistryKey() == World.NETHER) || (upperSectionsSize >= 34 && mc.world.getRegistryKey() == World.OVERWORLD))) {
						try {
							if (!tickexploitChunks.contains(oldpos) && !oldChunks.contains(oldpos) && !newChunks.contains(oldpos)) {
								newChunks.add(oldpos);
								if (save.get()) {
									saveNewChunkData(oldpos);
								}
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}

				isNewGeneration = false;
				foundAnyOre = false;
				if (liquidexploit.get() || oldchunksdetector.get()) {
					for (int x = 0; x < 16; x++) {
						for (int y = mc.world.getBottomY(); y < mc.world.getTopY(); y++) {
							for (int z = 0; z < 16; z++) {
								if (liquidexploit.get()) {
									FluidState fluid = chunk.getFluidState(x, y, z);
									if (!oldChunks.contains(oldpos) && !tickexploitChunks.contains(oldpos) && !newChunks.contains(oldpos) && !fluid.isEmpty() && !fluid.isStill()) {
										oldChunks.add(oldpos);
										if (save.get()){
											saveOldChunkData(oldpos);
										}
										return;
									}
								}
								if (!foundAnyOre && isOreBlock(chunk.getBlockState(new BlockPos(x, y, z)).getBlock()) && mc.world.getRegistryKey().getValue().toString().toLowerCase().contains("overworld")) foundAnyOre = true;
								if (!isNewGeneration && y<256 && y>=0 && (chunk.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.COPPER_ORE || chunk.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.DEEPSLATE_COPPER_ORE) && mc.world.getRegistryKey().getValue().toString().toLowerCase().contains("overworld")) isNewGeneration = true;
							}
						}
					}
				}
				if (oldchunksdetector.get() && !oldChunks.contains(oldpos) && !tickexploitChunks.contains(oldpos) && !newChunks.contains(oldpos) && foundAnyOre == true && isNewGeneration == false && mc.world.getRegistryKey().getValue().toString().toLowerCase().contains("overworld")) {
					oldChunks.add(oldpos);
					if (save.get()){
						saveOldChunkData(oldpos);
					}
				}
			}
		}
	}
	private int readSafeVarInt(PacketByteBuf buf) {
		int value = 0;
		int position = 0;
		byte currentByte;

		for (int i = 0; i < 5; i++) {  // Read at most 5 bytes
			if (!buf.isReadable()) {
				return -1; // Buffer exhausted
			}
			currentByte = buf.readByte();
			value |= (currentByte & 0x7F) << position;

			if ((currentByte & 0x80) == 0) {
				return value; // VarInt complete
			}

			position += 7;
		}

		// If we've read 5 bytes and still haven't seen the end, return the current value
		return value;
	}

	private boolean isOreBlock(Block block) {
		return block == Blocks.COAL_ORE
				|| block == Blocks.COPPER_ORE
				|| block == Blocks.DEEPSLATE_COPPER_ORE
				|| block == Blocks.IRON_ORE
				|| block == Blocks.DEEPSLATE_IRON_ORE
				|| block == Blocks.GOLD_ORE
				|| block == Blocks.DEEPSLATE_GOLD_ORE
				|| block == Blocks.LAPIS_ORE
				|| block == Blocks.DEEPSLATE_LAPIS_ORE
				|| block == Blocks.DIAMOND_ORE
				|| block == Blocks.DEEPSLATE_DIAMOND_ORE
				|| block == Blocks.REDSTONE_ORE
				|| block == Blocks.DEEPSLATE_REDSTONE_ORE
				|| block == Blocks.EMERALD_ORE
				|| block == Blocks.DEEPSLATE_EMERALD_ORE;
	}
	private void loadData() {
		try {
			List<String> allLines = Files.readAllLines(Paths.get("TrouserStreak/NewChunks/"+serverip+"/"+world+"/OldChunkData.txt"));

			for (String line : allLines) {
				String s = line;
				if (s !=null){
					String[] array = s.split(", ");
					if (array.length==2) {
						int X = Integer.parseInt(array[0].replaceAll("\\[", "").replaceAll("\\]", ""));
						int Z = Integer.parseInt(array[1].replaceAll("\\[", "").replaceAll("\\]", ""));
						oldpos = new ChunkPos(X, Z);
						if (!oldChunks.contains(oldpos) && !tickexploitChunks.contains(oldpos) && !newChunks.contains(oldpos)) {
							oldChunks.add(oldpos);
						}
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			List<String> allLines = Files.readAllLines(Paths.get("TrouserStreak/NewChunks/"+serverip+"/"+world+"/NewChunkData.txt"));

			for (String line : allLines) {
				String s = line;
				if (s !=null){
					String[] array = s.split(", ");
					if (array.length==2) {
						int X = Integer.parseInt(array[0].replaceAll("\\[", "").replaceAll("\\]", ""));
						int Z = Integer.parseInt(array[1].replaceAll("\\[", "").replaceAll("\\]", ""));
						chunkPos = new ChunkPos(X, Z);
						if (!tickexploitChunks.contains(chunkPos) && !newChunks.contains(chunkPos) && !oldChunks.contains(chunkPos)) {
							newChunks.add(chunkPos);
						}
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			List<String> allLines = Files.readAllLines(Paths.get("TrouserStreak/NewChunks/"+serverip+"/"+world+"/BlockExploitChunkData.txt"));

			for (String line : allLines) {
				String s = line;
				if (s !=null){
					String[] array = s.split(", ");
					if (array.length==2) {
						int X = Integer.parseInt(array[0].replaceAll("\\[", "").replaceAll("\\]", ""));
						int Z = Integer.parseInt(array[1].replaceAll("\\[", "").replaceAll("\\]", ""));
						chunkPos = new ChunkPos(X, Z);
						if (!tickexploitChunks.contains(chunkPos) && !newChunks.contains(chunkPos) && !oldChunks.contains(chunkPos)) {
							tickexploitChunks.add(chunkPos);
						}
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	private void saveNewChunkData(ChunkPos chunkpos) {
		try {
			new File("TrouserStreak/NewChunks/"+serverip+"/"+world).mkdirs();
			FileWriter writer = new FileWriter("TrouserStreak/NewChunks/"+serverip+"/"+world+"/NewChunkData.txt", true);
			writer.write(String.valueOf(chunkpos));
			writer.write("\r\n");   // write new line
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	private void saveOldChunkData(ChunkPos chunkpos) {
		try {
			new File("TrouserStreak/NewChunks/"+serverip+"/"+world).mkdirs();
			FileWriter writer = new FileWriter("TrouserStreak/NewChunks/"+serverip+"/"+world+"/OldChunkData.txt", true);
			writer.write(String.valueOf(chunkpos));
			writer.write("\r\n");   // write new line
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	private void saveBlockExploitChunkData(ChunkPos chunkpos) {
		try {
			new File("TrouserStreak/NewChunks/"+serverip+"/"+world).mkdirs();
			FileWriter writer = new FileWriter("TrouserStreak/NewChunks/"+serverip+"/"+world+"/BlockExploitChunkData.txt", true);
			writer.write(String.valueOf(chunkpos));
			writer.write("\r\n");   // write new line
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}