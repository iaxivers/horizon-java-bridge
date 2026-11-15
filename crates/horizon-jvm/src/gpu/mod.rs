//! The headless client's GPU: 26.3's `renderpearl` device (blaze3d's `GpuDevice`) carried out
//! on wgpu.
//!
//! The Java side (`HorizonGpuDevice` and its textures, buffers, encoder and passes) is a
//! renderpearl *backend*: vanilla's `FrontendGpuDevice` keeps doing its checks, compiles each
//! pipeline's GLSL to SPIR-V with shaderc and reflects it, and hands the backend the SPIR-V,
//! the vertex layout and the uniforms. Each backend call is one native here: textures,
//! views, samplers and buffers are wgpu objects by id; a render pass is recorded and
//! encoded when it ends; copies and clears are submitted at once, so queue writes in between
//! land in order. Mods that draw with the GPU (Xaero's minimap: its picture-in-picture
//! texture) draw into real textures, which the HUD reads back (`Gpu.read`).
//!
//! Coordinates are Vulkan's, as 26.3's Vulkan backend has them: NDC y = -1 is row 0 (naga's
//! SPIR-V reader flips y for wgpu, so front faces turn clockwise there), clip depth 0..1.

pub mod spirv;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, jstring};
use jni::{JNIEnv, NativeMethod};
use std::collections::{HashMap, HashSet};
use std::ffi::c_void;
use std::sync::Mutex;

static GPU: Mutex<Option<Gpu>> = Mutex::new(None);
/// Where the game keeps its window's Vulkan instance, for this device to share: a second
/// instance's device enumeration crashed in the Vulkan loader (Fedora 43, RADV). Set by the game.
pub static SHARED_INSTANCE: std::sync::OnceLock<fn() -> Option<(wgpu::Instance, wgpu::Adapter)>> =
    std::sync::OnceLock::new();

/// This device's instance, once made, for the game's window to share: [`instance`].
static OWN_INSTANCE: Mutex<Option<(wgpu::Instance, wgpu::Adapter)>> = Mutex::new(None);

/// The Vulkan instance and adapter this device made, if it did.
pub fn instance() -> Option<(wgpu::Instance, wgpu::Adapter)> {
    OWN_INSTANCE
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

/// `HORIZON_GPU_TRACE=1`: each pass is logged (its targets, draws and pipelines).
static TRACE: std::sync::LazyLock<bool> =
    std::sync::LazyLock::new(|| std::env::var_os("HORIZON_GPU_TRACE").is_some());
/// Set once opening the device failed (not tried again).
static FAILED: Mutex<bool> = Mutex::new(false);

/// renderpearl's `GpuFormat`, in its declaration order.
const FORMATS: [&str; 56] = [
    "R8_UNORM",
    "R8_SNORM",
    "RG8_UNORM",
    "RG8_SNORM",
    "RGB8_UNORM",
    "RGB8_SNORM",
    "RGBA8_UNORM",
    "RGBA8_SNORM",
    "R16_UNORM",
    "R16_SNORM",
    "RG16_UNORM",
    "RG16_SNORM",
    "RGB16_UNORM",
    "RGB16_SNORM",
    "RGBA16_UNORM",
    "RGBA16_SNORM",
    "R8_UINT",
    "R8_SINT",
    "RG8_UINT",
    "RG8_SINT",
    "RGB8_UINT",
    "RGB8_SINT",
    "RGBA8_UINT",
    "RGBA8_SINT",
    "R16_UINT",
    "R16_SINT",
    "RG16_UINT",
    "RG16_SINT",
    "RGB16_UINT",
    "RGB16_SINT",
    "RGBA16_UINT",
    "RGBA16_SINT",
    "R32_UINT",
    "R32_SINT",
    "RG32_UINT",
    "RG32_SINT",
    "RGB32_UINT",
    "RGB32_SINT",
    "RGBA32_UINT",
    "RGBA32_SINT",
    "R16_FLOAT",
    "RG16_FLOAT",
    "RGB16_FLOAT",
    "RGBA16_FLOAT",
    "R32_FLOAT",
    "RG32_FLOAT",
    "RGB32_FLOAT",
    "RGBA32_FLOAT",
    "RGB10A2_UNORM",
    "RGB10A2_UINT",
    "RG11B10_FLOAT",
    "D32_FLOAT",
    "D32_FLOAT_S8_UINT",
    "D24_UNORM_S8_UINT",
    "D16_UNORM",
    "S8_UINT",
];

fn format_name(ordinal: i32) -> &'static str {
    FORMATS.get(ordinal as usize).copied().unwrap_or("?")
}

/// A texture format for a renderpearl one (three-channel 8-bit formats have none: wgpu has
/// no RGB8 textures).
fn texture_format(name: &str) -> Option<wgpu::TextureFormat> {
    use wgpu::TextureFormat as F;
    Some(match name {
        "R8_UNORM" => F::R8Unorm,
        "R8_SNORM" => F::R8Snorm,
        "RG8_UNORM" => F::Rg8Unorm,
        "RG8_SNORM" => F::Rg8Snorm,
        "RGBA8_UNORM" => F::Rgba8Unorm,
        "RGBA8_SNORM" => F::Rgba8Snorm,
        "R16_UNORM" => F::R16Unorm,
        "R16_SNORM" => F::R16Snorm,
        "RG16_UNORM" => F::Rg16Unorm,
        "RG16_SNORM" => F::Rg16Snorm,
        "RGBA16_UNORM" => F::Rgba16Unorm,
        "RGBA16_SNORM" => F::Rgba16Snorm,
        "R8_UINT" => F::R8Uint,
        "R8_SINT" => F::R8Sint,
        "RG8_UINT" => F::Rg8Uint,
        "RG8_SINT" => F::Rg8Sint,
        "RGBA8_UINT" => F::Rgba8Uint,
        "RGBA8_SINT" => F::Rgba8Sint,
        "R16_UINT" => F::R16Uint,
        "R16_SINT" => F::R16Sint,
        "RG16_UINT" => F::Rg16Uint,
        "RG16_SINT" => F::Rg16Sint,
        "RGBA16_UINT" => F::Rgba16Uint,
        "RGBA16_SINT" => F::Rgba16Sint,
        "R32_UINT" => F::R32Uint,
        "R32_SINT" => F::R32Sint,
        "RG32_UINT" => F::Rg32Uint,
        "RG32_SINT" => F::Rg32Sint,
        "RGBA32_UINT" => F::Rgba32Uint,
        "RGBA32_SINT" => F::Rgba32Sint,
        "R16_FLOAT" => F::R16Float,
        "RG16_FLOAT" => F::Rg16Float,
        "RGBA16_FLOAT" => F::Rgba16Float,
        "R32_FLOAT" => F::R32Float,
        "RG32_FLOAT" => F::Rg32Float,
        "RGBA32_FLOAT" => F::Rgba32Float,
        "RGB10A2_UNORM" => F::Rgb10a2Unorm,
        "RGB10A2_UINT" => F::Rgb10a2Uint,
        "RG11B10_FLOAT" => F::Rg11b10Ufloat,
        "D32_FLOAT" => F::Depth32Float,
        "D32_FLOAT_S8_UINT" => F::Depth32FloatStencil8,
        "D24_UNORM_S8_UINT" => F::Depth24PlusStencil8,
        "D16_UNORM" => F::Depth16Unorm,
        "S8_UINT" => F::Stencil8,
        _ => return None,
    })
}

/// A vertex attribute's format (three-component 8- and 16-bit ones read as four: vanilla's
/// normals are padded to four bytes).
fn vertex_format(name: &str) -> Option<wgpu::VertexFormat> {
    use wgpu::VertexFormat as V;
    Some(match name {
        "R8_UNORM" => V::Unorm8,
        "R8_SNORM" => V::Snorm8,
        "RG8_UNORM" => V::Unorm8x2,
        "RG8_SNORM" => V::Snorm8x2,
        "RGB8_UNORM" | "RGBA8_UNORM" => V::Unorm8x4,
        "RGB8_SNORM" | "RGBA8_SNORM" => V::Snorm8x4,
        "R16_UNORM" => V::Unorm16,
        "R16_SNORM" => V::Snorm16,
        "RG16_UNORM" => V::Unorm16x2,
        "RG16_SNORM" => V::Snorm16x2,
        "RGB16_UNORM" | "RGBA16_UNORM" => V::Unorm16x4,
        "RGB16_SNORM" | "RGBA16_SNORM" => V::Snorm16x4,
        "R8_UINT" => V::Uint8,
        "R8_SINT" => V::Sint8,
        "RG8_UINT" => V::Uint8x2,
        "RG8_SINT" => V::Sint8x2,
        "RGB8_UINT" | "RGBA8_UINT" => V::Uint8x4,
        "RGB8_SINT" | "RGBA8_SINT" => V::Sint8x4,
        "R16_UINT" => V::Uint16,
        "R16_SINT" => V::Sint16,
        "RG16_UINT" => V::Uint16x2,
        "RG16_SINT" => V::Sint16x2,
        "RGB16_UINT" | "RGBA16_UINT" => V::Uint16x4,
        "RGB16_SINT" | "RGBA16_SINT" => V::Sint16x4,
        "R32_UINT" => V::Uint32,
        "R32_SINT" => V::Sint32,
        "RG32_UINT" => V::Uint32x2,
        "RG32_SINT" => V::Sint32x2,
        "RGB32_UINT" => V::Uint32x3,
        "RGB32_SINT" => V::Sint32x3,
        "RGBA32_UINT" => V::Uint32x4,
        "RGBA32_SINT" => V::Sint32x4,
        "R16_FLOAT" => V::Float16,
        "RG16_FLOAT" => V::Float16x2,
        "RGB16_FLOAT" | "RGBA16_FLOAT" => V::Float16x4,
        "R32_FLOAT" => V::Float32,
        "RG32_FLOAT" => V::Float32x2,
        "RGB32_FLOAT" => V::Float32x3,
        "RGBA32_FLOAT" => V::Float32x4,
        "RGB10A2_UNORM" => V::Unorm10_10_10_2,
        _ => return None,
    })
}

struct Texture {
    texture: wgpu::Texture,
    format: wgpu::TextureFormat,
    layers: u32,
}

struct View {
    view: wgpu::TextureView,
    texture: i64,
}

struct Buffer {
    buffer: wgpu::Buffer,
    size: u64,
}

/// A uniform's kind in a pipeline (renderpearl's `UniformType`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Uniform {
    Sampler,
    Buffer,
    TexelBuffer,
}

/// A pipeline as renderpearl described it; wgpu pipelines are made per depth format.
struct Pipeline {
    name: String,
    vertex: Option<wgpu::ShaderModule>,
    fragment: wgpu::ShaderModule,
    buffers: Vec<(u32, u64, wgpu::VertexStepMode, Vec<wgpu::VertexAttribute>)>,
    uniforms: Vec<Uniform>,
    /// The (group, binding) pairs the shaders use.
    used: HashSet<(u32, u32)>,
    depth: Option<(wgpu::CompareFunction, bool, f32, i32)>,
    primitive: wgpu::PrimitiveState,
    targets: Vec<Option<wgpu::ColorTargetState>>,
    made: HashMap<Option<wgpu::TextureFormat>, wgpu::RenderPipeline>,
}

enum Object {
    Texture(Texture),
    View(View),
    Sampler(wgpu::Sampler),
    Buffer(Buffer),
    Pipeline(Box<Pipeline>),
}

#[derive(Clone, Debug)]
enum Bound {
    Buffer(i64, u64, u64),
    Texture(i64, i64),
}

#[derive(Debug)]
enum Cmd {
    Pipeline(i64),
    Uniform(u32, Option<Bound>),
    Vertex(u32, i64, u64, u64),
    Index(i64, wgpu::IndexFormat),
    Scissor(Option<[u32; 4]>),
    Draw(u32, u32, u32, u32),
    DrawIndexed(u32, u32, u32, i32, u32),
}

struct Pass {
    colors: Vec<Option<(i64, Option<wgpu::Color>)>>,
    depth: Option<(i64, Option<f32>)>,
    area: [u32; 4],
    cmds: Vec<Cmd>,
}

struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    description: String,
    objects: HashMap<i64, Object>,
    next: i64,
    pass: Option<Pass>,
    /// Problems logged once each.
    logged: HashSet<String>,
}

impl Gpu {
    fn open() -> Result<Gpu, String> {
        // One Vulkan instance and adapter a process (see SHARED_INSTANCE): the window's when
        // it has them; the devices are enumerated once.
        let adapter = match SHARED_INSTANCE.get().and_then(|from| from()) {
            Some((_, adapter)) => adapter,
            None => {
                let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
                descriptor.backends = (wgpu::Backends::VULKAN | wgpu::Backends::GL).with_env();
                let instance = wgpu::Instance::new(descriptor.with_env());
                let adapter =
                    pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
                        power_preference: wgpu::PowerPreference::HighPerformance,
                        compatible_surface: None,
                        force_fallback_adapter: false,
                        ..Default::default()
                    }))
                    .map_err(|e| format!("no adapter: {e}"))?;
                *OWN_INSTANCE.lock().unwrap_or_else(|e| e.into_inner()) =
                    Some((instance, adapter.clone()));
                adapter
            }
        };
        let info = adapter.get_info();
        let features = adapter.features()
            & (wgpu::Features::TEXTURE_FORMAT_16BIT_NORM
                | wgpu::Features::DEPTH32FLOAT_STENCIL8
                | wgpu::Features::RG11B10UFLOAT_RENDERABLE
                | wgpu::Features::TEXTURE_ADAPTER_SPECIFIC_FORMAT_FEATURES);
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("Java mods' GPU"),
            required_features: features,
            required_limits: adapter.limits(),
            ..Default::default()
        }))
        .map_err(|e| format!("no device: {e}"))?;
        device.on_uncaptured_error(std::sync::Arc::new(|e| {
            // Logged a few times a kind: a broken draw fails every frame.
            static SEEN: Mutex<Option<HashMap<String, u32>>> = Mutex::new(None);
            let text = error_text(&e);
            let mut seen = SEEN.lock().unwrap_or_else(|e| e.into_inner());
            let count = seen
                .get_or_insert_with(HashMap::new)
                .entry(text.chars().take(120).collect())
                .or_insert(0);
            *count += 1;
            if *count <= 3 {
                log::warn!("Java mods' GPU: {text}");
            }
        }));
        let limits = device.limits();
        let description = serde_json::json!({
            "name": info.name,
            "backend": format!("{:?}", info.backend),
            "driver": format!("{} {}", info.driver, info.driver_info).trim(),
            "maxTexture": limits.max_texture_dimension_2d,
            "uniformAlign": limits.min_uniform_buffer_offset_alignment,
            "maxBuffer": limits.max_buffer_size,
            "maxColorAttachments": limits.max_color_attachments,
        })
        .to_string();
        log::info!(
            "Java mods' GPU: {} ({:?}) for the headless client",
            info.name,
            info.backend
        );
        Ok(Gpu {
            device,
            queue,
            description,
            objects: HashMap::new(),
            next: 1,
            pass: None,
            logged: HashSet::new(),
        })
    }

    fn add(&mut self, o: Object) -> i64 {
        let id = self.next;
        self.next += 1;
        self.objects.insert(id, o);
        id
    }

    fn once(&mut self, what: String) {
        if self.logged.insert(what.clone()) {
            log::warn!("Java mods' GPU: {what}");
        }
    }

    fn texture(&self, id: i64) -> Option<&Texture> {
        match self.objects.get(&id) {
            Some(Object::Texture(t)) => Some(t),
            _ => None,
        }
    }

    fn view(&self, id: i64) -> Option<&View> {
        match self.objects.get(&id) {
            Some(Object::View(v)) => Some(v),
            _ => None,
        }
    }

    fn buffer(&self, id: i64) -> Option<&Buffer> {
        match self.objects.get(&id) {
            Some(Object::Buffer(b)) => Some(b),
            _ => None,
        }
    }

    fn create_texture(&mut self, format: i32, w: u32, h: u32, layers: u32, mips: u32) -> i64 {
        let name = format_name(format);
        let Some(format) = texture_format(name) else {
            self.once(format!("no texture format for {name}"));
            return 0;
        };
        let features = format.guaranteed_format_features(self.device.features());
        let mut usage = wgpu::TextureUsages::COPY_DST
            | wgpu::TextureUsages::COPY_SRC
            | wgpu::TextureUsages::TEXTURE_BINDING;
        if features
            .allowed_usages
            .contains(wgpu::TextureUsages::RENDER_ATTACHMENT)
        {
            usage |= wgpu::TextureUsages::RENDER_ATTACHMENT;
        }
        let texture = self.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("Java mods' texture"),
            size: wgpu::Extent3d {
                width: w.max(1),
                height: h.max(1),
                depth_or_array_layers: layers.max(1),
            },
            mip_level_count: mips.max(1),
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format,
            usage,
            view_formats: &[],
        });
        self.add(Object::Texture(Texture {
            texture,
            format,
            layers: layers.max(1),
        }))
    }

    fn create_view(&mut self, texture: i64, base_mip: u32, mips: u32, cube: bool) -> i64 {
        let Some(t) = self.texture(texture) else {
            return 0;
        };
        let dimension = if cube && t.layers == 6 {
            wgpu::TextureViewDimension::Cube
        } else if t.layers > 1 {
            wgpu::TextureViewDimension::D2Array
        } else {
            wgpu::TextureViewDimension::D2
        };
        let view = t.texture.create_view(&wgpu::TextureViewDescriptor {
            label: Some("Java mods' texture view"),
            dimension: Some(dimension),
            base_mip_level: base_mip,
            mip_level_count: Some(mips.max(1)),
            ..Default::default()
        });
        self.add(Object::View(View { view, texture }))
    }

    fn create_sampler(&mut self, u: i32, v: i32, min: i32, mag: i32, aniso: i32, lod: f64) -> i64 {
        let address = |m| {
            if m == 0 {
                wgpu::AddressMode::Repeat
            } else {
                wgpu::AddressMode::ClampToEdge
            }
        };
        let filter = |f| {
            if f == 0 {
                wgpu::FilterMode::Nearest
            } else {
                wgpu::FilterMode::Linear
            }
        };
        // renderpearl's Vulkan sampler: mipmaps linear unless the max LOD is about 0.
        let max_lod = if lod < 0.0 { 1000.0 } else { lod };
        let mipmap = if max_lod > 0.25 {
            wgpu::MipmapFilterMode::Linear
        } else {
            wgpu::MipmapFilterMode::Nearest
        };
        let linear = min == 1 && mag == 1 && mipmap == wgpu::MipmapFilterMode::Linear;
        let sampler = self.device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("Java mods' sampler"),
            address_mode_u: address(u),
            address_mode_v: address(v),
            address_mode_w: wgpu::AddressMode::ClampToEdge,
            mag_filter: filter(mag),
            min_filter: filter(min),
            mipmap_filter: mipmap,
            lod_min_clamp: 0.0,
            lod_max_clamp: max_lod.max(0.25) as f32,
            compare: None,
            anisotropy_clamp: if linear { aniso.clamp(1, 16) as u16 } else { 1 },
            border_color: None,
        });
        self.add(Object::Sampler(sampler))
    }

    fn create_buffer(&mut self, size: u64) -> i64 {
        // Every use a renderpearl buffer can have: transient blocks serve all of them. Sizes go
        // up to 16 bytes: naga sizes a uniform block as std140 rounds it (a 40-byte Fog block
        // binds 48), where Vulkan reads only the members.
        let size = size.max(16).div_ceil(16) * 16;
        let buffer = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("Java mods' buffer"),
            size,
            usage: wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC
                | wgpu::BufferUsages::VERTEX
                | wgpu::BufferUsages::INDEX
                | wgpu::BufferUsages::UNIFORM
                | wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::INDIRECT,
            mapped_at_creation: false,
        });
        self.add(Object::Buffer(Buffer { buffer, size }))
    }

    /// `queue.write_buffer` wants 4-byte multiples: an unaligned write reads the bytes around
    /// it back first.
    fn write_buffer(&mut self, id: i64, offset: u64, data: &[u8]) {
        let Some(b) = self.buffer(id) else {
            return;
        };
        if offset + data.len() as u64 > b.size {
            self.once("a buffer write past its end".into());
            return;
        }
        let start = offset / 4 * 4;
        let end = (offset + data.len() as u64).div_ceil(4) * 4;
        if start == offset && end == offset + data.len() as u64 {
            self.queue.write_buffer(&b.buffer, offset, data);
            return;
        }
        let mut whole = self.read_buffer(id, start, end - start);
        let at = (offset - start) as usize;
        whole[at..at + data.len()].copy_from_slice(data);
        let b = self.buffer(id).unwrap();
        self.queue.write_buffer(&b.buffer, start, &whole);
    }

    /// A buffer's bytes, waiting for the GPU.
    fn read_buffer(&mut self, id: i64, offset: u64, len: u64) -> Vec<u8> {
        let Some(b) = self.buffer(id) else {
            return vec![0; len as usize];
        };
        let start = offset / 4 * 4;
        let end = ((offset + len).div_ceil(4) * 4).min(b.size);
        if end <= start {
            return vec![0; len as usize];
        }
        let staging = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("Java mods' readback"),
            size: end - start,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_buffer_to_buffer(&b.buffer, start, &staging, 0, end - start);
        self.queue.submit([encoder.finish()]);
        let bytes = self.map(&staging);
        let at = (offset - start) as usize;
        let mut out = vec![0; len as usize];
        let n = (bytes.len().saturating_sub(at)).min(out.len());
        out[..n].copy_from_slice(&bytes[at..at + n]);
        out
    }

    fn map(&self, staging: &wgpu::Buffer) -> Vec<u8> {
        let (tx, rx) = std::sync::mpsc::channel();
        staging.map_async(wgpu::MapMode::Read, .., move |r| {
            let _ = tx.send(r);
        });
        let _ = self.device.poll(wgpu::PollType::wait_indefinitely());
        match rx.recv() {
            Ok(Ok(())) => {
                let bytes = staging
                    .get_mapped_range(..)
                    .map(|v| v.to_vec())
                    .unwrap_or_default();
                staging.unmap();
                bytes
            }
            _ => Vec::new(),
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn write_texture(
        &mut self,
        id: i64,
        data: &[u8],
        row_pixels: u32,
        mip: u32,
        layer: u32,
        x: u32,
        y: u32,
        w: u32,
        h: u32,
    ) {
        let Some(t) = self.texture(id) else {
            return;
        };
        let block = t.format.block_copy_size(None).unwrap_or(4);
        self.queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &t.texture,
                mip_level: mip,
                origin: wgpu::Origin3d { x, y, z: layer },
                aspect: wgpu::TextureAspect::All,
            },
            data,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(row_pixels.max(w) * block),
                rows_per_image: Some(h),
            },
            wgpu::Extent3d {
                width: w,
                height: h,
                depth_or_array_layers: 1,
            },
        );
    }

    /// A texture's region, tightly packed, waiting for the GPU.
    #[allow(clippy::too_many_arguments)]
    fn read_texture(&mut self, id: i64, mip: u32, x: u32, y: u32, w: u32, h: u32) -> Vec<u8> {
        let Some(t) = self.texture(id) else {
            return Vec::new();
        };
        let block = t
            .format
            .block_copy_size(Some(wgpu::TextureAspect::All))
            .unwrap_or(4);
        let row = w * block;
        let padded =
            row.div_ceil(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT) * wgpu::COPY_BYTES_PER_ROW_ALIGNMENT;
        let staging = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("Java mods' texture readback"),
            size: (padded * h.max(1)) as u64,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_texture_to_buffer(
            wgpu::TexelCopyTextureInfo {
                texture: &t.texture,
                mip_level: mip,
                origin: wgpu::Origin3d { x, y, z: 0 },
                aspect: wgpu::TextureAspect::All,
            },
            wgpu::TexelCopyBufferInfo {
                buffer: &staging,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(padded),
                    rows_per_image: Some(h),
                },
            },
            wgpu::Extent3d {
                width: w,
                height: h,
                depth_or_array_layers: 1,
            },
        );
        self.queue.submit([encoder.finish()]);
        let bytes = self.map(&staging);
        if bytes.is_empty() {
            return Vec::new();
        }
        let mut out = Vec::with_capacity((row * h) as usize);
        for r in 0..h {
            let at = (r * padded) as usize;
            out.extend_from_slice(&bytes[at..at + row as usize]);
        }
        out
    }

    /// A pass that only clears (color and/or depth), over a whole mip level.
    fn clear(&mut self, color: Option<(i64, wgpu::Color)>, depth: Option<(i64, f32)>, mip: u32) {
        let view_of = |g: &Gpu, id: i64| {
            g.texture(id).map(|t| {
                t.texture.create_view(&wgpu::TextureViewDescriptor {
                    base_mip_level: mip,
                    mip_level_count: Some(1),
                    base_array_layer: 0,
                    array_layer_count: Some(1),
                    dimension: Some(wgpu::TextureViewDimension::D2),
                    ..Default::default()
                })
            })
        };
        let color_view = color.and_then(|(id, _)| view_of(self, id));
        let depth_view = depth.and_then(|(id, _)| view_of(self, id));
        let depth_format = depth.and_then(|(id, _)| self.texture(id).map(|t| t.format));
        let mut encoder = self.device.create_command_encoder(&Default::default());
        {
            let attachments = [color_view
                .as_ref()
                .map(|view| wgpu::RenderPassColorAttachment {
                    view,
                    resolve_target: None,
                    depth_slice: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(color.unwrap().1),
                        store: wgpu::StoreOp::Store,
                    },
                })];
            let _pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("Java mods' clear"),
                color_attachments: if color_view.is_some() {
                    &attachments
                } else {
                    &[]
                },
                depth_stencil_attachment: depth_view.as_ref().map(|view| {
                    wgpu::RenderPassDepthStencilAttachment {
                        view,
                        depth_ops: depth_format.filter(|f| f.has_depth_aspect()).map(|_| {
                            wgpu::Operations {
                                load: wgpu::LoadOp::Clear(depth.unwrap().1),
                                store: wgpu::StoreOp::Store,
                            }
                        }),
                        stencil_ops: depth_format.filter(|f| f.has_stencil_aspect()).map(|_| {
                            wgpu::Operations {
                                load: wgpu::LoadOp::Clear(0),
                                store: wgpu::StoreOp::Store,
                            }
                        }),
                    }
                }),
                ..Default::default()
            });
        }
        self.queue.submit([encoder.finish()]);
    }

    fn create_pipeline(
        &mut self,
        desc: &serde_json::Value,
        vertex: Option<&[u8]>,
        fragment: &[u8],
    ) -> i64 {
        let name = desc["name"].as_str().unwrap_or("?").to_owned();
        match self.pipeline(desc, vertex, fragment) {
            Ok(p) => self.add(Object::Pipeline(Box::new(p))),
            Err(e) => {
                log::warn!("Java mods' GPU: pipeline {name}: {e}");
                0
            }
        }
    }

    fn module(
        &self,
        spv: &[u8],
        name: &str,
        used: &mut HashSet<(u32, u32)>,
    ) -> Result<wgpu::ShaderModule, String> {
        let module = parse(spv, used)?;
        let scope = self.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let shader = self
            .device
            .create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some(name),
                source: wgpu::ShaderSource::Naga(std::borrow::Cow::Owned(module)),
            });
        if let Some(e) = pollster::block_on(scope.pop()) {
            return Err(format!("shader: {}", error_text(&e)));
        }
        Ok(shader)
    }

    fn pipeline(
        &mut self,
        d: &serde_json::Value,
        vertex: Option<&[u8]>,
        fragment: &[u8],
    ) -> Result<Pipeline, String> {
        use serde_json::Value;
        let name = d["name"].as_str().unwrap_or("?").to_owned();
        let mut used = HashSet::new();
        let vertex = match vertex {
            Some(v) => Some(self.module(v, &name, &mut used)?),
            None => None,
        };
        let fragment = self.module(fragment, &name, &mut used)?;
        let mut buffers = Vec::new();
        for b in d["vertexBuffers"].as_array().into_iter().flatten() {
            let slot = b["slot"].as_u64().unwrap_or(0) as u32;
            let step = if b["step"].as_u64().unwrap_or(0) > 0 {
                wgpu::VertexStepMode::Instance
            } else {
                wgpu::VertexStepMode::Vertex
            };
            let mut attributes = Vec::new();
            for a in d["attribs"].as_array().into_iter().flatten() {
                if a["buffer"].as_u64() != Some(slot as u64) {
                    continue;
                }
                let f = a["format"].as_str().unwrap_or("");
                let format = vertex_format(f).ok_or(format!("vertex format {f}"))?;
                attributes.push(wgpu::VertexAttribute {
                    format,
                    offset: a["offset"].as_u64().unwrap_or(0),
                    shader_location: a["location"].as_u64().unwrap_or(0) as u32,
                });
            }
            buffers.push((slot, b["stride"].as_u64().unwrap_or(0), step, attributes));
        }
        let uniforms = d["uniforms"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|u| match u["type"].as_str() {
                Some("COMBINED_IMAGE_SAMPLER") => Uniform::Sampler,
                Some("TEXEL_BUFFER") => Uniform::TexelBuffer,
                _ => Uniform::Buffer,
            })
            .collect::<Vec<_>>();
        if uniforms.contains(&Uniform::TexelBuffer) {
            return Err("texel buffers aren't supported".into());
        }
        let compare = |s: &str| match s {
            "ALWAYS_PASS" => wgpu::CompareFunction::Always,
            "LESS_THAN" => wgpu::CompareFunction::Less,
            "LESS_THAN_OR_EQUAL" => wgpu::CompareFunction::LessEqual,
            "EQUAL" => wgpu::CompareFunction::Equal,
            "NOT_EQUAL" => wgpu::CompareFunction::NotEqual,
            "GREATER_THAN_OR_EQUAL" => wgpu::CompareFunction::GreaterEqual,
            "GREATER_THAN" => wgpu::CompareFunction::Greater,
            _ => wgpu::CompareFunction::Never,
        };
        let depth = match &d["depth"] {
            Value::Object(o) => Some((
                compare(o["test"].as_str().unwrap_or("")),
                o["write"].as_bool().unwrap_or(false),
                o["biasScale"].as_f64().unwrap_or(0.0) as f32,
                o["biasConstant"].as_f64().unwrap_or(0.0) as i32,
            )),
            _ => None,
        };
        let topology = match d["topology"].as_str().unwrap_or("") {
            "DEBUG_LINES" => wgpu::PrimitiveTopology::LineList,
            "DEBUG_LINE_STRIP" => wgpu::PrimitiveTopology::LineStrip,
            "POINTS" => wgpu::PrimitiveTopology::PointList,
            "TRIANGLE_STRIP" => wgpu::PrimitiveTopology::TriangleStrip,
            _ => wgpu::PrimitiveTopology::TriangleList,
        };
        let primitive = wgpu::PrimitiveState {
            topology,
            strip_index_format: None,
            // renderpearl's Vulkan pipelines: clockwise in Vulkan's rows, which is
            // counter-clockwise in GL's NDC; naga's y flip turns that clockwise for wgpu.
            front_face: wgpu::FrontFace::Cw,
            cull_mode: d["cull"]
                .as_bool()
                .unwrap_or(false)
                .then_some(wgpu::Face::Back),
            unclipped_depth: false,
            polygon_mode: wgpu::PolygonMode::Fill,
            conservative: false,
        };
        let factor = |s: &str| {
            use wgpu::BlendFactor as B;
            match s {
                "CONSTANT_ALPHA" | "CONSTANT_COLOR" => B::Constant,
                "DST_ALPHA" => B::DstAlpha,
                "DST_COLOR" => B::Dst,
                "ONE" => B::One,
                "ONE_MINUS_CONSTANT_ALPHA" | "ONE_MINUS_CONSTANT_COLOR" => B::OneMinusConstant,
                "ONE_MINUS_DST_ALPHA" => B::OneMinusDstAlpha,
                "ONE_MINUS_DST_COLOR" => B::OneMinusDst,
                "ONE_MINUS_SRC_ALPHA" => B::OneMinusSrcAlpha,
                "ONE_MINUS_SRC_COLOR" => B::OneMinusSrc,
                "SRC_ALPHA" => B::SrcAlpha,
                "SRC_ALPHA_SATURATE" => B::SrcAlphaSaturated,
                "SRC_COLOR" => B::Src,
                _ => B::Zero,
            }
        };
        let component = |v: &Value| wgpu::BlendComponent {
            src_factor: factor(v["src"].as_str().unwrap_or("ONE")),
            dst_factor: factor(v["dst"].as_str().unwrap_or("ZERO")),
            operation: match v["op"].as_str().unwrap_or("ADD") {
                "SUBTRACT" => wgpu::BlendOperation::Subtract,
                "REVERSE_SUBTRACT" => wgpu::BlendOperation::ReverseSubtract,
                "MIN" => wgpu::BlendOperation::Min,
                "MAX" => wgpu::BlendOperation::Max,
                _ => wgpu::BlendOperation::Add,
            },
        };
        let mut targets = Vec::new();
        for t in d["targets"].as_array().into_iter().flatten() {
            if t.is_null() {
                targets.push(None);
                continue;
            }
            let f = t["format"].as_str().unwrap_or("");
            let format = texture_format(f).ok_or(format!("target format {f}"))?;
            let blend = t
                .get("blend")
                .filter(|b| b.is_object())
                .map(|b| wgpu::BlendState {
                    color: component(&b["color"]),
                    alpha: component(&b["alpha"]),
                });
            targets.push(Some(wgpu::ColorTargetState {
                format,
                blend,
                write_mask: wgpu::ColorWrites::from_bits_truncate(
                    t["mask"].as_u64().unwrap_or(15) as u32
                ),
            }));
        }
        let mut p = Pipeline {
            name,
            vertex,
            fragment,
            buffers,
            uniforms,
            used,
            depth,
            primitive,
            targets,
            made: HashMap::new(),
        };
        // The common variant now, so its errors show at compile time.
        self.make(&mut p, None)?;
        Ok(p)
    }

    /// The wgpu pipeline of `p` for a pass with this depth format.
    fn make(
        &self,
        p: &mut Pipeline,
        depth: Option<wgpu::TextureFormat>,
    ) -> Result<wgpu::RenderPipeline, String> {
        if let Some(made) = p.made.get(&depth) {
            return Ok(made.clone());
        }
        let Some(vertex) = &p.vertex else {
            return Err("no vertex shader".into());
        };
        let layouts: Vec<wgpu::VertexBufferLayout> = {
            let max = p.buffers.iter().map(|b| b.0 + 1).max().unwrap_or(0);
            (0..max)
                .map(|slot| match p.buffers.iter().find(|b| b.0 == slot) {
                    Some((_, stride, step, attributes)) => wgpu::VertexBufferLayout {
                        array_stride: *stride,
                        step_mode: *step,
                        attributes,
                    },
                    None => wgpu::VertexBufferLayout {
                        array_stride: 0,
                        step_mode: wgpu::VertexStepMode::Vertex,
                        attributes: &[],
                    },
                })
                .collect()
        };
        let buffers: Vec<Option<wgpu::VertexBufferLayout>> =
            layouts.into_iter().map(Some).collect();
        let depth_stencil = depth.map(|format| {
            let (compare, write, scale, constant) =
                p.depth
                    .unwrap_or((wgpu::CompareFunction::Always, false, 0.0, 0));
            wgpu::DepthStencilState {
                format,
                depth_write_enabled: Some(write && format.has_depth_aspect()),
                depth_compare: Some(if format.has_depth_aspect() {
                    compare
                } else {
                    wgpu::CompareFunction::Always
                }),
                stencil: Default::default(),
                bias: wgpu::DepthBiasState {
                    constant,
                    slope_scale: scale,
                    clamp: 0.0,
                },
            }
        });
        let scope = self.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let pipeline = self
            .device
            .create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(&p.name),
                layout: None,
                vertex: wgpu::VertexState {
                    module: vertex,
                    entry_point: Some("main"),
                    compilation_options: Default::default(),
                    buffers: &buffers,
                },
                primitive: p.primitive,
                depth_stencil,
                multisample: Default::default(),
                fragment: Some(wgpu::FragmentState {
                    module: &p.fragment,
                    entry_point: Some("main"),
                    compilation_options: Default::default(),
                    targets: &p.targets,
                }),
                multiview_mask: None,
                cache: None,
            });
        if let Some(e) = pollster::block_on(scope.pop()) {
            return Err(error_text(&e));
        }
        p.made.insert(depth, pipeline.clone());
        Ok(pipeline)
    }

    /// Encodes and submits the recorded pass.
    fn end_pass(&mut self) {
        let Some(pass) = self.pass.take() else {
            return;
        };
        if *TRACE {
            let draws = pass
                .cmds
                .iter()
                .filter(|c| matches!(c, Cmd::Draw(..) | Cmd::DrawIndexed(..)))
                .count();
            let pipelines: Vec<String> = pass
                .cmds
                .iter()
                .filter_map(|c| match c {
                    Cmd::Pipeline(id) => match self.objects.get(id) {
                        Some(Object::Pipeline(p)) => Some(p.name.clone()),
                        _ => Some(format!("#{id}?")),
                    },
                    _ => None,
                })
                .collect();
            log::info!(
                "Java mods' GPU: pass colors {:?} depth {:?} area {:?}: {draws} draws, pipelines {pipelines:?}",
                pass.colors,
                pass.depth,
                pass.area
            );
            self.trace_first_draw(&pass);
        }
        let mut colors = Vec::new();
        for c in &pass.colors {
            colors.push(
                c.as_ref()
                    .and_then(|(id, clear)| self.view(*id).map(|v| (v.view.clone(), *clear))),
            );
        }
        let depth = pass.depth.and_then(|(id, clear)| {
            let v = self.view(id)?;
            let format = self.texture(v.texture)?.format;
            Some((v.view.clone(), clear, format))
        });
        let depth_format = depth.as_ref().map(|d| d.2);
        // Each draw's pipeline, made for this pass's depth format first (no borrow of self in
        // the pass).
        let mut made = HashMap::new();
        for cmd in &pass.cmds {
            if let Cmd::Pipeline(id) = cmd {
                if made.contains_key(id) {
                    continue;
                }
                let Some(Object::Pipeline(mut p)) = self.objects.remove(id) else {
                    continue;
                };
                let result = self.make(&mut p, depth_format);
                let info = (p.uniforms.clone(), p.used.clone(), p.name.clone());
                self.objects.insert(*id, Object::Pipeline(p));
                match result {
                    Ok(pipeline) => {
                        made.insert(*id, Some((pipeline, info)));
                    }
                    Err(e) => {
                        self.once(format!(
                            "pipeline {} with depth {depth_format:?}: {e}",
                            info.2
                        ));
                        made.insert(*id, None);
                    }
                }
            }
        }
        let mut encoder = self.device.create_command_encoder(&Default::default());
        {
            let attachments: Vec<Option<wgpu::RenderPassColorAttachment>> = colors
                .iter()
                .map(|c| {
                    c.as_ref()
                        .map(|(view, clear)| wgpu::RenderPassColorAttachment {
                            view,
                            resolve_target: None,
                            depth_slice: None,
                            ops: wgpu::Operations {
                                load: match clear {
                                    Some(c) => wgpu::LoadOp::Clear(*c),
                                    None => wgpu::LoadOp::Load,
                                },
                                store: wgpu::StoreOp::Store,
                            },
                        })
                })
                .collect();
            let mut rp = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("Java mods' render pass"),
                color_attachments: &attachments,
                depth_stencil_attachment: depth.as_ref().map(|(view, clear, format)| {
                    wgpu::RenderPassDepthStencilAttachment {
                        view,
                        depth_ops: format.has_depth_aspect().then_some(wgpu::Operations {
                            load: match clear {
                                Some(d) => wgpu::LoadOp::Clear(*d),
                                None => wgpu::LoadOp::Load,
                            },
                            store: wgpu::StoreOp::Store,
                        }),
                        stencil_ops: format.has_stencil_aspect().then_some(wgpu::Operations {
                            load: wgpu::LoadOp::Load,
                            store: wgpu::StoreOp::Store,
                        }),
                    }
                }),
                ..Default::default()
            });
            let [ax, ay, aw, ah] = pass.area;
            let mut current: Option<&(
                wgpu::RenderPipeline,
                (Vec<Uniform>, HashSet<(u32, u32)>, String),
            )> = None;
            let mut uniforms: HashMap<u32, Bound> = HashMap::new();
            let mut dirty = true;
            let mut skipped = Vec::new();
            for cmd in &pass.cmds {
                match cmd {
                    Cmd::Pipeline(id) => {
                        current = made.get(id).and_then(|m| m.as_ref());
                        if let Some((pipeline, _)) = current {
                            rp.set_pipeline(pipeline);
                        }
                        dirty = true;
                    }
                    Cmd::Uniform(index, bound) => {
                        match bound {
                            Some(b) => uniforms.insert(*index, b.clone()),
                            None => uniforms.remove(index),
                        };
                        dirty = true;
                    }
                    Cmd::Vertex(slot, id, offset, len) => {
                        if let Some(b) = self.buffer(*id) {
                            let end = (offset + len).min(b.size);
                            if end > *offset {
                                rp.set_vertex_buffer(*slot, b.buffer.slice(*offset..end));
                            }
                        }
                    }
                    Cmd::Index(id, format) => {
                        if let Some(b) = self.buffer(*id) {
                            rp.set_index_buffer(b.buffer.slice(..), *format);
                        }
                    }
                    Cmd::Scissor(rect) => {
                        let [x, y, w, h] = rect.unwrap_or([ax, ay, aw, ah]);
                        rp.set_scissor_rect(x, y, w, h);
                    }
                    Cmd::Draw(..) | Cmd::DrawIndexed(..) => {
                        let Some((_, (kinds, used, name))) = current else {
                            continue;
                        };
                        if dirty {
                            match self.bind_groups(current.unwrap(), kinds, used, &uniforms) {
                                Ok(groups) => {
                                    for (i, g) in groups.iter().enumerate() {
                                        rp.set_bind_group(i as u32, g, &[]);
                                    }
                                    dirty = false;
                                }
                                Err(e) => {
                                    skipped.push(format!("{name}: {e}"));
                                    continue;
                                }
                            }
                        }
                        match *cmd {
                            Cmd::Draw(count, instances, first, first_instance) => rp.draw(
                                first..first + count,
                                first_instance..first_instance + instances,
                            ),
                            Cmd::DrawIndexed(count, instances, first, base, first_instance) => rp
                                .draw_indexed(
                                    first..first + count,
                                    base,
                                    first_instance..first_instance + instances,
                                ),
                            _ => {}
                        }
                    }
                }
            }
            drop(rp);
            for s in skipped {
                self.once(format!("a draw skipped: {s}"));
            }
        }
        self.queue.submit([encoder.finish()]);
        if *TRACE
            && let Some(Some((id, _))) = pass.colors.first()
            && let Some(texture) = self.view(*id).map(|v| v.texture)
        {
            let size = self.texture(texture).map(|t| t.texture.size());
            if let Some(size) = size {
                let px = self.read_texture(texture, 0, 0, 0, size.width, size.height);
                let lit = px.chunks_exact(4).filter(|p| p[3] != 0).count();
                log::info!(
                    "Java mods' GPU: after the pass, {lit} pixels of texture {texture} aren't transparent"
                );
            }
        }
    }

    /// HORIZON_GPU_TRACE: the pass's first draw's inputs (its first vertices, indices and
    /// uniform buffers as floats).
    fn trace_first_draw(&mut self, pass: &Pass) {
        let mut vertex = None;
        let mut index = None;
        let mut uniforms: HashMap<u32, Bound> = HashMap::new();
        let mut pipeline = None;
        for cmd in &pass.cmds {
            match cmd {
                Cmd::Pipeline(id) => pipeline = Some(*id),
                Cmd::Uniform(i, b) => {
                    match b {
                        Some(b) => uniforms.insert(*i, b.clone()),
                        None => uniforms.remove(i),
                    };
                }
                Cmd::Vertex(0, id, offset, _) => vertex = Some((*id, *offset)),
                Cmd::Index(id, format) => index = Some((*id, *format)),
                Cmd::Draw(..) | Cmd::DrawIndexed(..) => {
                    let floats = |bytes: Vec<u8>| -> Vec<f32> {
                        bytes
                            .chunks_exact(4)
                            .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                            .collect()
                    };
                    let stride = match pipeline.and_then(|p| self.objects.get(&p)) {
                        Some(Object::Pipeline(p)) => p.buffers.first().map(|b| b.1).unwrap_or(0),
                        _ => 0,
                    };
                    let (first, base) = match cmd {
                        Cmd::DrawIndexed(_, _, first, base, _) => (*first, *base),
                        Cmd::Draw(_, _, first, _) => (0, *first as i32),
                        _ => (0, 0),
                    };
                    let indices = match (index, cmd) {
                        (Some((id, f)), Cmd::DrawIndexed(..)) => {
                            let size = if f == wgpu::IndexFormat::Uint32 { 4 } else { 2 };
                            let b = self.read_buffer(id, first as u64 * size, 6 * size);
                            b.chunks_exact(size as usize)
                                .map(|c| {
                                    if size == 4 {
                                        u32::from_le_bytes([c[0], c[1], c[2], c[3]])
                                    } else {
                                        u16::from_le_bytes([c[0], c[1]]) as u32
                                    }
                                })
                                .collect::<Vec<_>>()
                        }
                        _ => Vec::new(),
                    };
                    if let Some((id, offset)) = vertex {
                        let at = offset
                            + (base.max(0) as u64 + indices.first().copied().unwrap_or(0) as u64)
                                * stride;
                        log::info!(
                            "Java mods' GPU:   draw {cmd:?}, indices {indices:?}, stride {stride}, vertex 0: {:?}",
                            floats(self.read_buffer(id, at, stride.max(4) * 2))
                        );
                    }
                    let mut keys: Vec<_> = uniforms.keys().copied().collect();
                    keys.sort();
                    for k in keys {
                        if let Some(Bound::Buffer(id, offset, len)) = uniforms.get(&k) {
                            log::info!(
                                "Java mods' GPU:   uniform {k}: {:?}",
                                floats(self.read_buffer(*id, *offset, (*len).min(160)))
                            );
                        }
                    }
                    return;
                }
                _ => {}
            }
        }
    }

    fn bind_groups(
        &self,
        current: &(
            wgpu::RenderPipeline,
            (Vec<Uniform>, HashSet<(u32, u32)>, String),
        ),
        kinds: &[Uniform],
        used: &HashSet<(u32, u32)>,
        uniforms: &HashMap<u32, Bound>,
    ) -> Result<Vec<wgpu::BindGroup>, String> {
        let groups = used.iter().map(|u| u.0 + 1).max().unwrap_or(0);
        let mut out = Vec::new();
        for group in 0..groups {
            let mut entries = Vec::new();
            let mut bindings: Vec<u32> =
                used.iter().filter(|u| u.0 == group).map(|u| u.1).collect();
            bindings.sort();
            for binding in bindings {
                let kind = kinds
                    .get(binding as usize)
                    .copied()
                    .unwrap_or(Uniform::Buffer);
                let bound = uniforms
                    .get(&binding)
                    .ok_or(format!("uniform {binding} unbound"))?;
                let resource = match (group, kind, bound) {
                    (0, Uniform::Buffer, Bound::Buffer(id, offset, len)) => {
                        let b = self.buffer(*id).ok_or("a closed buffer")?;
                        wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                            buffer: &b.buffer,
                            offset: *offset,
                            size: std::num::NonZeroU64::new(
                                len.div_ceil(16)
                                    .saturating_mul(16)
                                    .min(b.size.saturating_sub(*offset)),
                            ),
                        })
                    }
                    (0, Uniform::Sampler, Bound::Texture(view, _)) => {
                        wgpu::BindingResource::TextureView(
                            &self.view(*view).ok_or("a closed view")?.view,
                        )
                    }
                    (spirv::SAMPLER_GROUP, Uniform::Sampler, Bound::Texture(_, sampler)) => {
                        match self.objects.get(sampler) {
                            Some(Object::Sampler(s)) => wgpu::BindingResource::Sampler(s),
                            _ => return Err("a closed sampler".into()),
                        }
                    }
                    _ => return Err(format!("uniform {binding} of the wrong kind")),
                };
                entries.push(wgpu::BindGroupEntry { binding, resource });
            }
            out.push(self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: None,
                layout: &current.0.get_bind_group_layout(group),
                entries: &entries,
            }));
        }
        Ok(out)
    }
}

/// A renderpearl SPIR-V module as naga's IR (its combined samplers split, validated), and the
/// (group, binding) pairs its entry points use.
fn parse(spv: &[u8], used: &mut HashSet<(u32, u32)>) -> Result<naga::Module, String> {
    if !spv.len().is_multiple_of(4) {
        return Err("SPIR-V length".into());
    }
    let words: Vec<u32> = spv
        .chunks_exact(4)
        .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect();
    let words = spirv::split_samplers(&words)?;
    let module = naga::front::spv::parse_u8_slice(
        bytemuck_bytes(&words),
        &naga::front::spv::Options {
            adjust_coordinate_space: true,
            strict_capabilities: false,
            block_ctx_dump_prefix: None,
        },
    )
    .map_err(|e| format!("SPIR-V: {e}"))?;
    let info = naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::all(),
    )
    .validate(&module)
    .map_err(|e| format!("validation: {e:?}"))?;
    for (i, _) in module.entry_points.iter().enumerate() {
        let ep = info.get_entry_point(i);
        for (handle, global) in module.global_variables.iter() {
            if let Some(binding) = &global.binding
                && !ep[handle].is_empty()
            {
                used.insert((binding.group, binding.binding));
            }
        }
    }
    Ok(module)
}

/// A wgpu error with its causes (its Display is only "Validation Error").
fn error_text(e: &wgpu::Error) -> String {
    let mut text = match e {
        wgpu::Error::Validation {
            description,
            source,
        } => format!("{description} ({source:?})"),
        other => format!("{other:?}"),
    };
    let mut source = std::error::Error::source(e);
    while let Some(s) = source {
        let s_text = s.to_string();
        if !text.contains(&s_text) {
            text.push_str(": ");
            text.push_str(&s_text);
        }
        source = s.source();
    }
    text
}

fn bytemuck_bytes(words: &[u32]) -> &[u8] {
    // SAFETY: u32s are plain bytes; the slice covers exactly their memory.
    unsafe { std::slice::from_raw_parts(words.as_ptr().cast::<u8>(), words.len() * 4) }
}

/// Bytes at a Java address (LWJGL's `MemoryUtil.memAddress`).
///
/// # Safety
/// The Java side passes the address and length of live memory it owns (a direct buffer).
unsafe fn bytes<'a>(address: jlong, len: jlong) -> &'a [u8] {
    if address == 0 || len <= 0 {
        return &[];
    }
    // SAFETY: see above.
    unsafe { std::slice::from_raw_parts(address as *const u8, len as usize) }
}

/// Opens the device now if it isn't (the game's window asks first, so this device comes first).
pub fn open_now() {
    with((), |_| ());
}

/// Runs `f` on the device (opened on first use); `default` when there is none.
fn with<T>(default: T, f: impl FnOnce(&mut Gpu) -> T) -> T {
    let mut guard = GPU.lock().unwrap_or_else(|e| e.into_inner());
    if guard.is_none() {
        let mut failed = FAILED.lock().unwrap_or_else(|e| e.into_inner());
        if *failed {
            return default;
        }
        match Gpu::open() {
            Ok(g) => *guard = Some(g),
            Err(e) => {
                log::warn!("Java mods' GPU: {e}");
                *failed = true;
                return default;
            }
        }
    }
    let gpu = guard.as_mut().unwrap();
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| f(gpu))) {
        Ok(v) => v,
        Err(_) => {
            log::warn!("Java mods' GPU: a call panicked");
            default
        }
    }
}

extern "system" fn native_open<'l>(env: JNIEnv<'l>, _: JClass<'l>) -> jstring {
    match with(None, |g| Some(g.description.clone())) {
        Some(s) => env
            .new_string(s)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

extern "system" fn native_texture(
    _: JNIEnv,
    _: JClass,
    format: jint,
    w: jint,
    h: jint,
    layers: jint,
    mips: jint,
) -> jlong {
    with(0, |g| {
        g.create_texture(format, w as u32, h as u32, layers as u32, mips as u32)
    })
}

extern "system" fn native_view(
    _: JNIEnv,
    _: JClass,
    texture: jlong,
    base: jint,
    mips: jint,
    cube: jboolean,
) -> jlong {
    with(0, |g| {
        g.create_view(texture, base as u32, mips as u32, cube != 0)
    })
}

extern "system" fn native_sampler(
    _: JNIEnv,
    _: JClass,
    u: jint,
    v: jint,
    min: jint,
    mag: jint,
    aniso: jint,
    lod: jdouble,
) -> jlong {
    with(0, |g| g.create_sampler(u, v, min, mag, aniso, lod))
}

extern "system" fn native_buffer(_: JNIEnv, _: JClass, size: jlong) -> jlong {
    with(0, |g| g.create_buffer(size as u64))
}

extern "system" fn native_release(_: JNIEnv, _: JClass, id: jlong) {
    with((), |g| {
        if let Some(Object::Texture(t)) = g.objects.remove(&id) {
            t.texture.destroy();
        }
    })
}

extern "system" fn native_write_buffer(
    _: JNIEnv,
    _: JClass,
    id: jlong,
    offset: jlong,
    address: jlong,
    len: jlong,
) {
    // SAFETY: Java passes live memory (Gpu.java).
    let data = unsafe { bytes(address, len) };
    with((), |g| g.write_buffer(id, offset as u64, data))
}

extern "system" fn native_read_buffer(
    _: JNIEnv,
    _: JClass,
    id: jlong,
    offset: jlong,
    address: jlong,
    len: jlong,
) {
    if address == 0 || len <= 0 {
        return;
    }
    let bytes = with(Vec::new(), |g| g.read_buffer(id, offset as u64, len as u64));
    // SAFETY: Java passes `len` writable bytes at `address`.
    unsafe {
        std::ptr::copy_nonoverlapping(
            bytes.as_ptr(),
            address as *mut u8,
            bytes.len().min(len as usize),
        );
    }
}

extern "system" fn native_copy_buffer(
    _: JNIEnv,
    _: JClass,
    src: jlong,
    src_offset: jlong,
    dst: jlong,
    dst_offset: jlong,
    len: jlong,
) {
    with((), |g| {
        let (Some(a), Some(b)) = (g.buffer(src), g.buffer(dst)) else {
            return;
        };
        if src_offset % 4 == 0 && dst_offset % 4 == 0 && len % 4 == 0 {
            let mut encoder = g.device.create_command_encoder(&Default::default());
            encoder.copy_buffer_to_buffer(
                &a.buffer,
                src_offset as u64,
                &b.buffer,
                dst_offset as u64,
                len as u64,
            );
            g.queue.submit([encoder.finish()]);
        } else {
            let data = g.read_buffer(src, src_offset as u64, len as u64);
            g.write_buffer(dst, dst_offset as u64, &data);
        }
    })
}

#[allow(clippy::too_many_arguments)]
extern "system" fn native_write_texture(
    _: JNIEnv,
    _: JClass,
    id: jlong,
    address: jlong,
    len: jlong,
    row_pixels: jint,
    mip: jint,
    layer: jint,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
) {
    // SAFETY: Java passes live memory (Gpu.java).
    let data = unsafe { bytes(address, len) };
    with((), |g| {
        g.write_texture(
            id,
            data,
            row_pixels as u32,
            mip as u32,
            layer as u32,
            x as u32,
            y as u32,
            w as u32,
            h as u32,
        )
    })
}

/// A texture's region as bytes (`byte[]`), tightly packed.
#[allow(clippy::too_many_arguments)]
extern "system" fn native_read_texture<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    id: jlong,
    mip: jint,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
) -> jni::sys::jbyteArray {
    let bytes = with(Vec::new(), |g| {
        g.read_texture(id, mip as u32, x as u32, y as u32, w as u32, h as u32)
    });
    env.byte_array_from_slice(&bytes)
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[allow(clippy::too_many_arguments)]
extern "system" fn native_copy_texture(
    _: JNIEnv,
    _: JClass,
    src: jlong,
    dst: jlong,
    mip: jint,
    dx: jint,
    dy: jint,
    sx: jint,
    sy: jint,
    w: jint,
    h: jint,
) {
    with((), |g| {
        let (Some(a), Some(b)) = (g.texture(src), g.texture(dst)) else {
            return;
        };
        let mut encoder = g.device.create_command_encoder(&Default::default());
        encoder.copy_texture_to_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &a.texture,
                mip_level: mip as u32,
                origin: wgpu::Origin3d {
                    x: sx as u32,
                    y: sy as u32,
                    z: 0,
                },
                aspect: wgpu::TextureAspect::All,
            },
            wgpu::TexelCopyTextureInfo {
                texture: &b.texture,
                mip_level: mip as u32,
                origin: wgpu::Origin3d {
                    x: dx as u32,
                    y: dy as u32,
                    z: 0,
                },
                aspect: wgpu::TextureAspect::All,
            },
            wgpu::Extent3d {
                width: w as u32,
                height: h as u32,
                depth_or_array_layers: 1,
            },
        );
        g.queue.submit([encoder.finish()]);
    })
}

#[allow(clippy::too_many_arguments)]
extern "system" fn native_clear(
    _: JNIEnv,
    _: JClass,
    color: jlong,
    r: jdouble,
    gr: jdouble,
    b: jdouble,
    a: jdouble,
    depth: jlong,
    d: jdouble,
    mip: jint,
) {
    with((), |g| {
        g.clear(
            (color != 0).then_some((color, wgpu::Color { r, g: gr, b, a })),
            (depth != 0).then_some((depth, d as f32)),
            mip as u32,
        )
    })
}

/// A pipeline from its description (JSON, `HorizonGpuDevice.compilePipeline`) and SPIR-V.
#[allow(clippy::too_many_arguments)]
extern "system" fn native_pipeline<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    desc: JString<'l>,
    vertex: jlong,
    vertex_len: jlong,
    fragment: jlong,
    fragment_len: jlong,
) -> jlong {
    let Ok(desc) = env.get_string(&desc).map(String::from) else {
        return 0;
    };
    let Ok(desc) = serde_json::from_str::<serde_json::Value>(&desc) else {
        return 0;
    };
    // SAFETY: Java passes the SPIR-V modules' live memory.
    let (v, f) = unsafe { (bytes(vertex, vertex_len), bytes(fragment, fragment_len)) };
    with(0, |g| {
        g.create_pipeline(&desc, (!v.is_empty()).then_some(v), f)
    })
}

/// Starts recording a pass: its color views and clears (`clears`: 4 per view, NaN for none),
/// its depth view and clear (NaN for none), its area.
#[allow(clippy::too_many_arguments)]
extern "system" fn native_begin_pass<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    views: jni::sys::jlongArray,
    clears: jni::sys::jdoubleArray,
    depth: jlong,
    depth_clear: jdouble,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
) {
    // SAFETY: the arrays are the call's arguments.
    let (views, clears) = unsafe {
        (
            jni::objects::JLongArray::from_raw(views),
            jni::objects::JDoubleArray::from_raw(clears),
        )
    };
    let n = env.get_array_length(&views).unwrap_or(0) as usize;
    let mut ids = vec![0i64; n];
    let mut cs = vec![0f64; n * 4];
    let _ = env.get_long_array_region(&views, 0, &mut ids);
    let _ = env.get_double_array_region(&clears, 0, &mut cs);
    let colors = ids
        .iter()
        .enumerate()
        .map(|(i, &id)| {
            (id != 0).then(|| {
                let c = &cs[i * 4..i * 4 + 4];
                (
                    id,
                    (!c[0].is_nan()).then_some(wgpu::Color {
                        r: c[0],
                        g: c[1],
                        b: c[2],
                        a: c[3],
                    }),
                )
            })
        })
        .collect();
    with((), |g| {
        if g.pass.is_some() {
            g.end_pass();
        }
        g.pass = Some(Pass {
            colors,
            depth: (depth != 0)
                .then_some((depth, (!depth_clear.is_nan()).then_some(depth_clear as f32))),
            area: [x as u32, y as u32, w as u32, h as u32],
            cmds: vec![Cmd::Scissor(None)],
        });
    })
}

fn record(cmd: Cmd) {
    with((), |g| {
        if let Some(p) = &mut g.pass {
            p.cmds.push(cmd);
        }
    })
}

extern "system" fn native_end_pass(_: JNIEnv, _: JClass) {
    with((), Gpu::end_pass)
}

extern "system" fn native_set_pipeline(_: JNIEnv, _: JClass, id: jlong) {
    record(Cmd::Pipeline(id))
}

/// A uniform: a buffer slice (`a` buffer, `b` offset, `c` length) when `kind` is 1, a view
/// and sampler (`a`, `b`) when 2, unbound when 0.
extern "system" fn native_set_uniform(
    _: JNIEnv,
    _: JClass,
    index: jint,
    kind: jint,
    a: jlong,
    b: jlong,
    c: jlong,
) {
    let bound = match kind {
        1 => Some(Bound::Buffer(a, b as u64, c as u64)),
        2 => Some(Bound::Texture(a, b)),
        _ => None,
    };
    record(Cmd::Uniform(index as u32, bound))
}

extern "system" fn native_set_vertex(
    _: JNIEnv,
    _: JClass,
    slot: jint,
    id: jlong,
    offset: jlong,
    len: jlong,
) {
    record(Cmd::Vertex(slot as u32, id, offset as u64, len as u64))
}

extern "system" fn native_set_index(_: JNIEnv, _: JClass, id: jlong, int: jboolean) {
    record(Cmd::Index(
        id,
        if int != 0 {
            wgpu::IndexFormat::Uint32
        } else {
            wgpu::IndexFormat::Uint16
        },
    ))
}

extern "system" fn native_scissor(_: JNIEnv, _: JClass, x: jint, y: jint, w: jint, h: jint) {
    record(Cmd::Scissor(
        (w > 0).then_some([x as u32, y as u32, w as u32, h as u32]),
    ))
}

extern "system" fn native_draw(
    _: JNIEnv,
    _: JClass,
    count: jint,
    instances: jint,
    first: jint,
    first_instance: jint,
) {
    record(Cmd::Draw(
        count as u32,
        instances as u32,
        first as u32,
        first_instance as u32,
    ))
}

extern "system" fn native_draw_indexed(
    _: JNIEnv,
    _: JClass,
    count: jint,
    instances: jint,
    first: jint,
    base: jint,
    first_instance: jint,
) {
    record(Cmd::DrawIndexed(
        count as u32,
        instances as u32,
        first as u32,
        base,
        first_instance as u32,
    ))
}

/// Registers `dev.horizonlab.bridge.Gpu`'s natives.
pub(crate) fn register(env: &mut JNIEnv) -> jni::errors::Result<()> {
    let class = env.find_class("dev/horizonlab/bridge/Gpu")?;
    let m = |name: &str, sig: &str, f: *mut c_void| NativeMethod {
        name: name.into(),
        sig: sig.into(),
        fn_ptr: f,
    };
    env.register_native_methods(
        &class,
        &[
            m("open", "()Ljava/lang/String;", native_open as *mut c_void),
            m("texture", "(IIIII)J", native_texture as *mut c_void),
            m("view", "(JIIZ)J", native_view as *mut c_void),
            m("sampler", "(IIIIID)J", native_sampler as *mut c_void),
            m("buffer", "(J)J", native_buffer as *mut c_void),
            m("release", "(J)V", native_release as *mut c_void),
            m("writeBuffer", "(JJJJ)V", native_write_buffer as *mut c_void),
            m("readBuffer", "(JJJJ)V", native_read_buffer as *mut c_void),
            m("copyBuffer", "(JJJJJ)V", native_copy_buffer as *mut c_void),
            m(
                "writeTexture",
                "(JJJIIIIIII)V",
                native_write_texture as *mut c_void,
            ),
            m(
                "readTexture",
                "(JIIIII)[B",
                native_read_texture as *mut c_void,
            ),
            m(
                "copyTexture",
                "(JJIIIIIII)V",
                native_copy_texture as *mut c_void,
            ),
            m("clear", "(JDDDDJDI)V", native_clear as *mut c_void),
            m(
                "pipeline",
                "(Ljava/lang/String;JJJJ)J",
                native_pipeline as *mut c_void,
            ),
            m(
                "beginPass",
                "([J[DJDIIII)V",
                native_begin_pass as *mut c_void,
            ),
            m("endPass", "()V", native_end_pass as *mut c_void),
            m("setPipeline", "(J)V", native_set_pipeline as *mut c_void),
            m("setUniform", "(IIJJJ)V", native_set_uniform as *mut c_void),
            m("setVertex", "(IJJJ)V", native_set_vertex as *mut c_void),
            m("setIndex", "(JZ)V", native_set_index as *mut c_void),
            m("scissor", "(IIII)V", native_scissor as *mut c_void),
            m("draw", "(IIII)V", native_draw as *mut c_void),
            m(
                "drawIndexed",
                "(IIIII)V",
                native_draw_indexed as *mut c_void,
            ),
        ],
    )
}

#[cfg(test)]
mod tests {
    /// Every SPIR-V module in `HORIZON_SPV_DIR` (renderpearl's, dumped with
    /// `HORIZON_JAVA_GPU_DUMP`, or tools' shaderc output) reads into naga.
    #[test]
    fn dumped_modules_parse() {
        let Some(dir) = std::env::var_os("HORIZON_SPV_DIR") else {
            return;
        };
        let mut failed = Vec::new();
        let mut n = 0;
        for entry in std::fs::read_dir(dir).unwrap().flatten() {
            let bytes = std::fs::read(entry.path()).unwrap();
            n += 1;
            match super::parse(&bytes, &mut Default::default()) {
                // Texel buffers (vanilla's clouds) have no wgpu binding: those pipelines fail.
                Err(e) if e.contains("image dimension") => {}
                Err(e) => failed.push(format!("{}: {e}", entry.path().display())),
                Ok(_) => {}
            }
        }
        assert!(
            failed.is_empty(),
            "{} of {n} failed:\n{}",
            failed.len(),
            failed.join("\n")
        );
    }
}
