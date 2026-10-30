use crate::{android, font::FontInfo, transaction::FontState};
use eframe::egui::{self, Color32, RichText, Vec2};
use serde_json::Value;
use std::{
    sync::{
        atomic::{AtomicUsize, Ordering},
        Mutex, OnceLock,
    },
    time::Duration,
};

struct Shared {
    message: String,
    error: bool,
    ready: bool,
    game_active: bool,
    busy: bool,
    current: Option<FontState>,
    font: Option<FontInfo>,
    filename: String,
    preview: Option<(Vec<u8>, [usize; 2])>,
    context: Option<egui::Context>,
}
impl Default for Shared {
    fn default() -> Self {
        Self {
            message: "Choose a font. Make it yours.".into(),
            error: false,
            ready: false,
            game_active: false,
            busy: false,
            current: None,
            font: None,
            filename: String::new(),
            preview: None,
            context: None,
        }
    }
}
static SHARED: OnceLock<Mutex<Shared>> = OnceLock::new();
pub static UI_GENERATION: AtomicUsize = AtomicUsize::new(0);
fn shared() -> &'static Mutex<Shared> {
    SHARED.get_or_init(Default::default)
}

pub fn event(event: Value) {
    let mut state = shared().lock().unwrap();
    if let Some(message) = event.get("message").and_then(Value::as_str) {
        state.message = message.into();
    }
    if let Some(value) = event.get("error").and_then(Value::as_bool) {
        state.error = value;
    }
    if let Some(value) = event.get("ready").and_then(Value::as_bool) {
        state.ready = value;
    }
    if let Some(value) = event.get("busy").and_then(Value::as_bool) {
        state.busy = value;
    }
    if let Some(result) = event.get("result") {
        if let Some(current) = result.get("current") {
            if let Ok(current) = serde_json::from_value(current.clone()) {
                state.current = Some(current);
            }
        }
        if let Some(value) = result.get("game_active").and_then(Value::as_bool) {
            state.game_active = value;
        }
    }
    let context = state.context.clone();
    drop(state);
    if let Some(ctx) = context {
        ctx.request_repaint();
    }
}

pub fn selected(font: FontInfo, filename: String, rgba: Vec<u8>, size: [usize; 2]) {
    let mut state = shared().lock().unwrap();
    state.font = Some(font);
    state.filename = filename
        .chars()
        .filter(|c| !c.is_control())
        .take(100)
        .collect();
    state.preview = Some((rgba, size));
    let context = state.context.clone();
    drop(state);
    if let Some(ctx) = context {
        ctx.request_repaint();
    }
}

const BACKGROUND: Color32 = Color32::from_rgb(15, 18, 22);
const CARD: Color32 = Color32::from_rgb(24, 29, 35);
const LIME: Color32 = Color32::from_rgb(181, 243, 111);
const TEXT: Color32 = Color32::from_rgb(248, 247, 243);
const MUTED: Color32 = Color32::from_rgb(159, 169, 179);

struct VFontApp {
    preview: Option<egui::TextureHandle>,
}
impl VFontApp {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let mut style = (*cc.egui_ctx.style()).clone();
        style.visuals = egui::Visuals::dark();
        style.visuals.override_text_color = Some(TEXT);
        style.visuals.panel_fill = BACKGROUND;
        style.visuals.selection.bg_fill = Color32::from_rgb(66, 90, 45);
        style.spacing.button_padding = egui::vec2(16., 14.);
        style.spacing.item_spacing = egui::vec2(12., 12.);
        style
            .text_styles
            .insert(egui::TextStyle::Body, egui::FontId::proportional(16.));
        style
            .text_styles
            .insert(egui::TextStyle::Button, egui::FontId::proportional(16.));
        cc.egui_ctx.set_style(style);
        shared().lock().unwrap().context = Some(cc.egui_ctx.clone());
        UI_GENERATION.fetch_add(1, Ordering::SeqCst);
        Self { preview: None }
    }
}

fn card(ui: &mut egui::Ui, content: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::default()
        .fill(CARD)
        .corner_radius(18)
        .inner_margin(20)
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            content(ui);
        });
}

impl eframe::App for VFontApp {
    fn update(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        let (message, error, ready, game, busy, current, font, filename, pending_preview) = {
            let mut state = shared().lock().unwrap();
            (
                state.message.clone(),
                state.error,
                state.ready,
                state.game_active,
                state.busy,
                state.current.clone(),
                state.font.clone(),
                state.filename.clone(),
                state.preview.take(),
            )
        };
        // Never hold our state lock while calling egui; Android callbacks use both.
        if let Some((rgba, size)) = pending_preview {
            self.preview = Some(ctx.load_texture(
                "font-preview",
                egui::ColorImage::from_rgba_unmultiplied(size, &rgba),
                egui::TextureOptions::LINEAR,
            ));
        }
        ctx.request_repaint_after(Duration::from_millis(500));
        egui::CentralPanel::default().frame(egui::Frame::default().fill(BACKGROUND).inner_margin(24)).show(ctx, |ui| {
            egui::ScrollArea::vertical().show(ui, |ui| {
                ui.add_space(12.);
                ui.label(RichText::new("VFONT").color(LIME).size(13.).strong().extra_letter_spacing(3.));
                ui.label(RichText::new("Your type.\nYour phone.").size(36.).strong());
                ui.label(RichText::new("Native fonts for your X200 Ultra.").color(MUTED));
                ui.add_space(12.);
                card(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(if ready {"SHIZUKU CONNECTED"} else {"SHIZUKU"}).size(12.).color(if ready {LIME} else {MUTED}));
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.add_enabled(!busy, egui::Button::new(if ready {"Check"} else {"Connect"})).clicked() {
                                android::action(if ready {"status"} else {"connect"});
                            }
                        });
                    });
                    if let Some(current) = &current {
                        ui.label(if current.target == crate::transaction::ROBOTO {"Current: system Roboto"} else {"Current: custom font"});
                    } else { ui.label(RichText::new("Connect to check your current font.").color(MUTED)); }
                    if !game {
                        ui.separator();
                        ui.label("Enable VFont in Ultra Game Mode once.");
                        if ui.add_enabled(!busy, egui::Button::new("Set up Game Cube")).clicked() { android::action("game"); }
                    } else { ui.label(RichText::new("VFont is active in Game Cube").size(13.).color(LIME)); }
                });
                ui.add_space(6.);
                card(ui, |ui| {
                    ui.label(RichText::new("01  /  CHOOSE YOUR FONT").size(12.).color(MUTED));
                    if let Some(info) = &font {
                        ui.label(RichText::new(&info.family).size(23.).strong());
                        ui.label(RichText::new(format!("{} · {} · {:.1} KB · {} glyphs", info.style, info.format, info.bytes as f32 / 1024., info.glyphs)).size(13.).color(MUTED));
                        if info.variable { ui.label(RichText::new("Variable font · default axis values").size(12.).color(MUTED)); }
                        if !info.color_formats.is_empty() { ui.label(RichText::new(format!("Color glyphs: {}", info.color_formats.join(", "))).size(12.).color(LIME)); }
                        if info.has_emoji { ui.label(RichText::new("Upper emoji row: selected font + fallback. Lower row: current system font. Apps with their own emoji can look different.").size(12.).color(MUTED)); }
                        if info.has_emoji && !info.has_text { ui.label(RichText::new("Emoji-only font: applying may return text to system fallback. Use a combined text/emoji font to keep custom text too.").size(12.).color(Color32::from_rgb(255, 210, 130))); }
                        ui.label(RichText::new(&filename).size(12.).color(MUTED));
                    } else {
                        ui.label(RichText::new("A font that feels like you.").size(22.).strong());
                        ui.label(RichText::new("TTF, OTF, TTC/OTC, WOFF or WOFF2. Includes variable and color fonts. Preview before applying.").color(MUTED));
                    }
                    if ui.add_enabled(!busy, egui::Button::new(if font.is_some() {"Choose another font"} else {"Choose font file  +"}).min_size(Vec2::new(ui.available_width(), 48.))).clicked() { android::action("pick"); }
                });
                if let Some(preview) = &self.preview {
                    card(ui, |ui| {
                        ui.label(RichText::new("02  /  NATIVE PREVIEW").size(12.).color(MUTED));
                        ui.add(egui::Image::new(preview).fit_to_exact_size(Vec2::new(ui.available_width(), ui.available_width() * 460. / 720.)));
                    });
                }
                card(ui, |ui| {
                    ui.label(RichText::new("03  /  MAKE IT YOURS").size(12.).color(MUTED));
                    ui.label("Tap Apply, then swipe open Game Cube’s performance panel. Leave CPU/GPU/FPS visible for up to 35 seconds.");
                    if busy { ui.horizontal(|ui| { ui.spinner(); ui.label("Working…"); }); }
                    ui.label(RichText::new(&message).color(if error { Color32::from_rgb(255, 165, 155) } else { MUTED }));
                    if ui.add_enabled(ready && game && !busy && font.is_some(), egui::Button::new(RichText::new("Apply font").color(BACKGROUND).strong()).fill(LIME).min_size(Vec2::new(ui.available_width(), 54.))).clicked() { android::action("apply"); }
                    if ui.add_enabled(ready && game && !busy, egui::Button::new("Restore system Roboto").min_size(Vec2::new(ui.available_width(), 46.))).clicked() { android::action("restore"); }
                });
                ui.label(RichText::new("LOCAL ONLY  ·  RUST + SHIZUKU").size(11.).color(MUTED));
                ui.add_space(12.);
            });
        });
    }
}

#[no_mangle]
pub fn android_main(app: android_activity::AndroidApp) {
    let options = eframe::NativeOptions {
        android_app: Some(app),
        renderer: eframe::Renderer::Glow,
        ..Default::default()
    };
    let _ = eframe::run_native(
        "VFont",
        options,
        Box::new(|cc| Ok(Box::new(VFontApp::new(cc)))),
    );
    shared().lock().unwrap().context = None;
}
