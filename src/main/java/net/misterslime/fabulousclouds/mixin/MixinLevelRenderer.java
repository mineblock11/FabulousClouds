package net.misterslime.fabulousclouds.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Matrix4f;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.misterslime.fabulousclouds.FabulousClouds;
import net.misterslime.fabulousclouds.NoiseCloudHandler;
import net.misterslime.fabulousclouds.config.FabulousCloudsConfig;
import net.misterslime.fabulousclouds.clouds.CloudTexture;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

@Mixin(LevelRenderer.class)
public final class MixinLevelRenderer {

    @Final
    @Shadow
    private static ResourceLocation CLOUDS_LOCATION;
    @Shadow
    private final int ticks;
    @Final
    @Shadow
    @NotNull
    private final Minecraft minecraft;
    @Shadow
    private int prevCloudX;
    @Shadow
    private int prevCloudY;
    @Shadow
    private int prevCloudZ;
    @Shadow
    @NotNull
    private Vec3 prevCloudColor;
    @Shadow
    @NotNull
    private CloudStatus prevCloudsType;
    @Shadow
    private boolean generateClouds;
    @Shadow
    @NotNull
    private VertexBuffer cloudBuffer;
    @Unique
    private boolean initializedClouds = false;

    public MixinLevelRenderer() {
        throw new NullPointerException("Null cannot be cast to non-null type.");
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    public void renderClouds(PoseStack poseStack, Matrix4f model, float tickDelta, double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
        FabulousCloudsConfig config = FabulousClouds.getConfig();
        if (FabulousClouds.getConfig().noise_clouds) {
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            registerClouds(textureManager);
            NoiseCloudHandler.update();
        }

        if (minecraft.level.dimension() == ClientLevel.OVERWORLD) {
            float cloudHeight = DimensionSpecialEffects.OverworldEffects.CLOUD_LEVEL;
            if (!Float.isNaN(cloudHeight)) {
                int i = 0;
                for(FabulousCloudsConfig.CloudLayer cloudLayer : config.cloud_layers) {
                    CloudTexture cloudTexture = NoiseCloudHandler.cloudTextures.get(i);
                    renderCloudLayer(poseStack, model, tickDelta, cameraX, cameraY, cameraZ, cloudHeight, cloudLayer.offset, cloudLayer.scale, cloudLayer.speed, cloudTexture.resourceLocation);
                    i++;
                }
            }

            if (config.enable_default_cloud_layer) {
                CloudTexture cloudTexture = NoiseCloudHandler.cloudTextures.get(NoiseCloudHandler.cloudTextures.size() - 1);
                renderCloudLayer(poseStack, model, tickDelta, cameraX, cameraY, cameraZ, cloudHeight, 0, 1, 1, cloudTexture.resourceLocation);
            }

            ci.cancel();
        }
    }

    private void registerClouds(TextureManager textureManager) {
        if (!this.initializedClouds) {
            Random random = new Random();

            NoiseCloudHandler.initCloudTextures(CLOUDS_LOCATION);

            for(CloudTexture cloudTexture : NoiseCloudHandler.cloudTextures) {
                cloudTexture.initNoise(random);

                DynamicTexture texture = cloudTexture.getNativeImage();
                textureManager.register(cloudTexture.resourceLocation, texture);
                cloudTexture.setTexture(texture);
            }

            this.initializedClouds = true;
        }
    }

    private void renderCloudLayer(PoseStack poseStack, Matrix4f model, float tickDelta, double cameraX, double cameraY, double cameraZ, float cloudHeight, float cloudOffset, float cloudScale, float speedMod, ResourceLocation resourceLocation) {
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(true);
        float scale = 12.0F * cloudScale;
        double speed = ((this.ticks + tickDelta) * (0.03F * speedMod));
        double posX = (cameraX + speed) / scale;
        double posY = (cloudHeight - (float) cameraY + cloudOffset) / cloudScale;
        double posZ = cameraZ / scale + 0.33000001311302185D;
        posX -= Math.floor(posX / 2048.0D) * 2048;
        posZ -= Math.floor(posZ / 2048.0D) * 2048;
        float adjustedX = (float) (posX - (double) Math.floor(posX));
        float adjustedY = (float) (posY / 4.0D - (double) Math.floor(posY / 4.0D)) * 4.0F;
        float adjustedZ = (float) (posZ - (double) Math.floor(posZ));
        Vec3 cloudColor = minecraft.level.getCloudColor(tickDelta);
        int floorX = (int) Math.floor(posX);
        int floorY = (int) Math.floor(posY / 4.0D);
        int floorZ = (int) Math.floor(posZ);
        if (floorX != this.prevCloudX || floorY != this.prevCloudY || floorZ != this.prevCloudZ || this.minecraft.options.getCloudsType() != this.prevCloudsType || this.prevCloudColor.distanceToSqr(cloudColor) > 2.0E-4D) {
            this.prevCloudX = floorX;
            this.prevCloudY = floorY;
            this.prevCloudZ = floorZ;
            this.prevCloudColor = cloudColor;
            this.prevCloudsType = this.minecraft.options.getCloudsType();
            this.generateClouds = true;
        }

        if (this.generateClouds) {
            this.generateClouds = false;
            Tesselator tessellator = Tesselator.getInstance();
            BufferBuilder bufferBuilder = tessellator.getBuilder();
            if (this.cloudBuffer != null) this.cloudBuffer.close();

            this.cloudBuffer = new VertexBuffer();
            this.buildCloudLayer(bufferBuilder, posX, posY, posZ, cloudScale, cloudColor);
            bufferBuilder.end();
            this.cloudBuffer.upload(bufferBuilder);
        }

        RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
        RenderSystem.setShaderTexture(0, resourceLocation);
        FogRenderer.levelFogColor();
        poseStack.pushPose();
        poseStack.scale(scale, cloudScale, scale);
        poseStack.translate(-adjustedX, adjustedY, -adjustedZ);
        if (this.cloudBuffer != null) {
            int cloudMainIndex = this.prevCloudsType == CloudStatus.FANCY ? 0 : 1;
          
            for (int cloudIndex = 1; cloudMainIndex <= cloudIndex; ++cloudMainIndex) {
                if (cloudMainIndex == 0) {
                    RenderSystem.colorMask(false, false, false, false);
                } else {
                    RenderSystem.colorMask(true, true, true, true);
                }

                ShaderInstance shader = RenderSystem.getShader();
                this.cloudBuffer.drawWithShader(poseStack.last().pose(), model, shader);
            }
        }

        poseStack.popPose();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private void buildCloudLayer(BufferBuilder bufferBuilder, double x, double y, double z, float scale, Vec3 color) {
        float f = 4.0f;
        float f2 = 0.00390625f;
        int n = 8;
        int n2 = 4;
        float f3 = 9.765625E-4f;
        float f4 = (float)Math.floor(x) * 0.00390625f;
        float f5 = (float)Math.floor(z) * 0.00390625f;
        float f6 = (float)color.x;
        float f7 = (float)color.y;
        float f8 = (float)color.z;
        float f9 = f6 * 0.9f;
        float f10 = f7 * 0.9f;
        float f11 = f8 * 0.9f;
        float f12 = f6 * 0.7f;
        float f13 = f7 * 0.7f;
        float f14 = f8 * 0.7f;
        float f15 = f6 * 0.8f;
        float f16 = f7 * 0.8f;
        float f17 = f8 * 0.8f;
        RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        float f18 = (float)Math.floor(y / 4.0) * 4.0f;
        if (this.prevCloudsType == CloudStatus.FANCY) {
            int i2 = (int) (8 / scale) / 2;

            for (int i = -i2; i <= i2; ++i) {
                for (int j = -i2; j <= i2; ++j) {
                    int n3;
                    float f19 = i * 8;
                    float f20 = j * 8;
                    if (f18 > -5.0f) {
                        bufferBuilder.vertex(f19 + 0.0f, f18 + 0.0f, f20 + 8.0f).uv((f19 + 0.0f) * 0.00390625f + f4, (f20 + 8.0f) * 0.00390625f + f5).color(f12, f13, f14, 0.8f).normal(0.0f, -1.0f, 0.0f).endVertex();
                        bufferBuilder.vertex(f19 + 8.0f, f18 + 0.0f, f20 + 8.0f).uv((f19 + 8.0f) * 0.00390625f + f4, (f20 + 8.0f) * 0.00390625f + f5).color(f12, f13, f14, 0.8f).normal(0.0f, -1.0f, 0.0f).endVertex();
                        bufferBuilder.vertex(f19 + 8.0f, f18 + 0.0f, f20 + 0.0f).uv((f19 + 8.0f) * 0.00390625f + f4, (f20 + 0.0f) * 0.00390625f + f5).color(f12, f13, f14, 0.8f).normal(0.0f, -1.0f, 0.0f).endVertex();
                        bufferBuilder.vertex(f19 + 0.0f, f18 + 0.0f, f20 + 0.0f).uv((f19 + 0.0f) * 0.00390625f + f4, (f20 + 0.0f) * 0.00390625f + f5).color(f12, f13, f14, 0.8f).normal(0.0f, -1.0f, 0.0f).endVertex();
                    }
                    if (f18 <= 5.0f) {
                        bufferBuilder.vertex(f19 + 0.0f, f18 + 4.0f - 9.765625E-4f, f20 + 8.0f).uv((f19 + 0.0f) * 0.00390625f + f4, (f20 + 8.0f) * 0.00390625f + f5).color(f6, f7, f8, 0.8f).normal(0.0f, 1.0f, 0.0f).endVertex();
                        bufferBuilder.vertex(f19 + 8.0f, f18 + 4.0f - 9.765625E-4f, f20 + 8.0f).uv((f19 + 8.0f) * 0.00390625f + f4, (f20 + 8.0f) * 0.00390625f + f5).color(f6, f7, f8, 0.8f).normal(0.0f, 1.0f, 0.0f).endVertex();
                        bufferBuilder.vertex(f19 + 8.0f, f18 + 4.0f - 9.765625E-4f, f20 + 0.0f).uv((f19 + 8.0f) * 0.00390625f + f4, (f20 + 0.0f) * 0.00390625f + f5).color(f6, f7, f8, 0.8f).normal(0.0f, 1.0f, 0.0f).endVertex();
                        bufferBuilder.vertex(f19 + 0.0f, f18 + 4.0f - 9.765625E-4f, f20 + 0.0f).uv((f19 + 0.0f) * 0.00390625f + f4, (f20 + 0.0f) * 0.00390625f + f5).color(f6, f7, f8, 0.8f).normal(0.0f, 1.0f, 0.0f).endVertex();
                    }
                    if (i > -1) {
                        for (n3 = 0; n3 < 8; ++n3) {
                            bufferBuilder.vertex(f19 + (float)n3 + 0.0f, f18 + 0.0f, f20 + 8.0f).uv((f19 + (float)n3 + 0.5f) * 0.00390625f + f4, (f20 + 8.0f) * 0.00390625f + f5).color(f9, f10, f11, 0.8f).normal(-1.0f, 0.0f, 0.0f).endVertex();
                            bufferBuilder.vertex(f19 + (float)n3 + 0.0f, f18 + 4.0f, f20 + 8.0f).uv((f19 + (float)n3 + 0.5f) * 0.00390625f + f4, (f20 + 8.0f) * 0.00390625f + f5).color(f9, f10, f11, 0.8f).normal(-1.0f, 0.0f, 0.0f).endVertex();
                            bufferBuilder.vertex(f19 + (float)n3 + 0.0f, f18 + 4.0f, f20 + 0.0f).uv((f19 + (float)n3 + 0.5f) * 0.00390625f + f4, (f20 + 0.0f) * 0.00390625f + f5).color(f9, f10, f11, 0.8f).normal(-1.0f, 0.0f, 0.0f).endVertex();
                            bufferBuilder.vertex(f19 + (float)n3 + 0.0f, f18 + 0.0f, f20 + 0.0f).uv((f19 + (float)n3 + 0.5f) * 0.00390625f + f4, (f20 + 0.0f) * 0.00390625f + f5).color(f9, f10, f11, 0.8f).normal(-1.0f, 0.0f, 0.0f).endVertex();
                        }
                    }
                    if (i <= 1) {
                        for (n3 = 0; n3 < 8; ++n3) {
                            bufferBuilder.vertex(f19 + (float)n3 + 1.0f - 9.765625E-4f, f18 + 0.0f, f20 + 8.0f).uv((f19 + (float)n3 + 0.5f) * 0.00390625f + f4, (f20 + 8.0f) * 0.00390625f + f5).color(f9, f10, f11, 0.8f).normal(1.0f, 0.0f, 0.0f).endVertex();
                            bufferBuilder.vertex(f19 + (float)n3 + 1.0f - 9.765625E-4f, f18 + 4.0f, f20 + 8.0f).uv((f19 + (float)n3 + 0.5f) * 0.00390625f + f4, (f20 + 8.0f) * 0.00390625f + f5).color(f9, f10, f11, 0.8f).normal(1.0f, 0.0f, 0.0f).endVertex();
                            bufferBuilder.vertex(f19 + (float)n3 + 1.0f - 9.765625E-4f, f18 + 4.0f, f20 + 0.0f).uv((f19 + (float)n3 + 0.5f) * 0.00390625f + f4, (f20 + 0.0f) * 0.00390625f + f5).color(f9, f10, f11, 0.8f).normal(1.0f, 0.0f, 0.0f).endVertex();
                            bufferBuilder.vertex(f19 + (float)n3 + 1.0f - 9.765625E-4f, f18 + 0.0f, f20 + 0.0f).uv((f19 + (float)n3 + 0.5f) * 0.00390625f + f4, (f20 + 0.0f) * 0.00390625f + f5).color(f9, f10, f11, 0.8f).normal(1.0f, 0.0f, 0.0f).endVertex();
                        }
                    }
                    if (j > -1) {
                        for (n3 = 0; n3 < 8; ++n3) {
                            bufferBuilder.vertex(f19 + 0.0f, f18 + 4.0f, f20 + (float)n3 + 0.0f).uv((f19 + 0.0f) * 0.00390625f + f4, (f20 + (float)n3 + 0.5f) * 0.00390625f + f5).color(f15, f16, f17, 0.8f).normal(0.0f, 0.0f, -1.0f).endVertex();
                            bufferBuilder.vertex(f19 + 8.0f, f18 + 4.0f, f20 + (float)n3 + 0.0f).uv((f19 + 8.0f) * 0.00390625f + f4, (f20 + (float)n3 + 0.5f) * 0.00390625f + f5).color(f15, f16, f17, 0.8f).normal(0.0f, 0.0f, -1.0f).endVertex();
                            bufferBuilder.vertex(f19 + 8.0f, f18 + 0.0f, f20 + (float)n3 + 0.0f).uv((f19 + 8.0f) * 0.00390625f + f4, (f20 + (float)n3 + 0.5f) * 0.00390625f + f5).color(f15, f16, f17, 0.8f).normal(0.0f, 0.0f, -1.0f).endVertex();
                            bufferBuilder.vertex(f19 + 0.0f, f18 + 0.0f, f20 + (float)n3 + 0.0f).uv((f19 + 0.0f) * 0.00390625f + f4, (f20 + (float)n3 + 0.5f) * 0.00390625f + f5).color(f15, f16, f17, 0.8f).normal(0.0f, 0.0f, -1.0f).endVertex();
                        }
                    }
                    if (j > 1) continue;
                    for (n3 = 0; n3 < 8; ++n3) {
                        bufferBuilder.vertex(f19 + 0.0f, f18 + 4.0f, f20 + (float)n3 + 1.0f - 9.765625E-4f).uv((f19 + 0.0f) * 0.00390625f + f4, (f20 + (float)n3 + 0.5f) * 0.00390625f + f5).color(f15, f16, f17, 0.8f).normal(0.0f, 0.0f, 1.0f).endVertex();
                        bufferBuilder.vertex(f19 + 8.0f, f18 + 4.0f, f20 + (float)n3 + 1.0f - 9.765625E-4f).uv((f19 + 8.0f) * 0.00390625f + f4, (f20 + (float)n3 + 0.5f) * 0.00390625f + f5).color(f15, f16, f17, 0.8f).normal(0.0f, 0.0f, 1.0f).endVertex();
                        bufferBuilder.vertex(f19 + 8.0f, f18 + 0.0f, f20 + (float)n3 + 1.0f - 9.765625E-4f).uv((f19 + 8.0f) * 0.00390625f + f4, (f20 + (float)n3 + 0.5f) * 0.00390625f + f5).color(f15, f16, f17, 0.8f).normal(0.0f, 0.0f, 1.0f).endVertex();
                        bufferBuilder.vertex(f19 + 0.0f, f18 + 0.0f, f20 + (float)n3 + 1.0f - 9.765625E-4f).uv((f19 + 0.0f) * 0.00390625f + f4, (f20 + (float)n3 + 0.5f) * 0.00390625f + f5).color(f15, f16, f17, 0.8f).normal(0.0f, 0.0f, 1.0f).endVertex();
                    }
                }
            }
        } else {
            boolean bl = true;
            int n4 = (int) (32 / scale);
            for (int i = -n4; i < n4; i += n4) {
                for (int j = -n4; j < n4; j += n4) {
                    bufferBuilder.vertex(i + 0, f18, j + n4).uv((float)(i + 0) * 0.00390625f + f4, (float)(j + n4) * 0.00390625f + f5).color(f6, f7, f8, 0.8f).normal(0.0f, -1.0f, 0.0f).endVertex();
                    bufferBuilder.vertex(i + n4, f18, j + n4).uv((float)(i + n4) * 0.00390625f + f4, (float)(j + n4) * 0.00390625f + f5).color(f6, f7, f8, 0.8f).normal(0.0f, -1.0f, 0.0f).endVertex();
                    bufferBuilder.vertex(i + n4, f18, j + 0).uv((float)(i + n4) * 0.00390625f + f4, (float)(j + 0) * 0.00390625f + f5).color(f6, f7, f8, 0.8f).normal(0.0f, -1.0f, 0.0f).endVertex();
                    bufferBuilder.vertex(i + 0, f18, j + 0).uv((float)(i + 0) * 0.00390625f + f4, (float)(j + 0) * 0.00390625f + f5).color(f6, f7, f8, 0.8f).normal(0.0f, -1.0f, 0.0f).endVertex();
                }
            }
        }
    }
}
