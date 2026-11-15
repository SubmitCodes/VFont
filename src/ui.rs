use crate::{android, font::FontInfo, transaction::FontState};
use eframe::egui::{self, pos2, vec2, Color32, FontId, RichText, Stroke};
use serde_json::Value;
use std::{
    sync::{
        atomic::{AtomicUsize, Ordering},
        Mutex, OnceLock,
    },
    time::Duration,
};

#[derive(Default)]
struct Shared {
    message: String,
    error: bool,
    ready: bool,
    supported: bool,
    command_verified: bool,
    experimental: bool,
    busy: bool,
    back: bool,
    current: Option<FontState>,
    font: Option<FontInfo>,
    filename: String,
    preview: Option<(Vec<u8>, [usize; 2])>,
    palette: Palette,
    insets: [f32; 4],
    context: Option<egui::Context>,
}
static SHARED: OnceLock<Mutex<Shared>> = OnceLock::new();
pub static UI_GENERATION: AtomicUsize = AtomicUsize::new(0);
fn shared() -> &'static Mutex<Shared> {
    SHARED.get_or_init(Default::default)
}

pub fn event(event: Value) {
    let mut state = shared().lock().unwrap();
    if let Some(theme) = event.get("theme") {
        state.palette = Palette::from_json(theme);
    }
    if let Some(values) = event.get("insets").and_then(Value::as_array) {
        if values.len() == 4 {
            for (inset, value) in state.insets.iter_mut().zip(values) {
                *inset = value.as_f64().unwrap_or(0.).clamp(0., 1000.) as f32;
            }
        }
    }
    if let Some(message) = event.get("message").and_then(Value::as_str) {
        state.message = message.into();
    }
    if let Some(value) = event.get("error").and_then(Value::as_bool) {
        state.error = value;
    }
    if let Some(value) = event.get("ready").and_then(Value::as_bool) {
        state.ready = value;
        if !value {
            state.command_verified = false;
            state.supported = false;
            state.experimental = false;
        }
    }
    if let Some(value) = event.get("busy").and_then(Value::as_bool) {
        state.busy = value;
    }
    if event.get("back").and_then(Value::as_bool) == Some(true) {
        state.back = true;
    }
    if event.get("cleared").and_then(Value::as_bool) == Some(true) {
        state.font = None;
        state.filename.clear();
        state.preview = None;
    }
    if let Some(result) = event.get("result") {
        if let Some(tested) = result
            .get("profile")
            .and_then(|p| p.get("tested"))
            .and_then(Value::as_bool)
        {
            state.experimental = !tested;
        }
        if let Some(value) = result.get("supported").and_then(Value::as_bool) {
            state.supported = value;
        }
        if let Some(current) = result.get("current") {
            state.current = serde_json::from_value(current.clone()).ok();
        }
        if let Some(value) = result.get("command_verified").and_then(Value::as_bool) {
            state.command_verified = value;
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

#[derive(Clone, Copy, PartialEq)]
struct Palette {
    dark: bool,
    motion: bool,
    background: Color32,
    surface: Color32,
    elevated: Color32,
    primary: Color32,
    on_primary: Color32,
    secondary: Color32,
    on_secondary: Color32,
    outline: Color32,
    text: Color32,
    muted: Color32,
    error: Color32,
}
impl Default for Palette {
    fn default() -> Self {
        Self {
            dark: true,
            motion: true,
            background: Color32::from_rgb(17, 20, 16),
            surface: Color32::from_rgb(26, 30, 24),
            elevated: Color32::from_rgb(39, 44, 36),
            primary: Color32::from_rgb(183, 209, 164),
            on_primary: Color32::from_rgb(36, 53, 25),
            secondary: Color32::from_rgb(60, 75, 51),
            on_secondary: Color32::from_rgb(218, 230, 207),
            outline: Color32::from_rgb(146, 157, 178),
            text: Color32::from_rgb(227, 230, 219),
            muted: Color32::from_rgb(193, 201, 183),
            error: Color32::from_rgb(255, 180, 171),
        }
    }
}
impl Palette {
    fn from_json(value: &Value) -> Self {
        let mut p = Self::default();
        let color = |key: &str, fallback: Color32| {
            value
                .get(key)
                .and_then(Value::as_u64)
                .map_or(fallback, |argb| {
                    Color32::from_rgb((argb >> 16) as u8, (argb >> 8) as u8, argb as u8)
                })
        };
        p.dark = value["dark"].as_bool().unwrap_or(p.dark);
        p.motion = value["motion"].as_bool().unwrap_or(p.motion);
        p.background = color("background", p.background);
        p.surface = color("surface", p.surface);
        p.elevated = color("elevated", p.elevated);
        p.primary = color("primary", p.primary);
        p.on_primary = color("on_primary", p.on_primary);
        p.secondary = color("secondary", p.secondary);
        p.on_secondary = color("on_secondary", p.on_secondary);
        p.outline = color("outline", p.outline);
        p.text = color("text", p.text);
        p.muted = color("muted", p.muted);
        p.error = color("error", p.error);
        p
    }
    fn duration(self) -> f32 {
        if self.motion {
            0.22
        } else {
            0.
        }
    }
}

#[derive(Clone, Copy)]
enum Icon {
    Info,
    Back,
}

fn icon(painter: &egui::Painter, center: egui::Pos2, kind: Icon, color: Color32, size: f32) {
    let k = size / 24.;
    let point = |x, y| center + vec2(x, y) * k;
    let stroke = Stroke::new(1.8 * k, color);
    let line = |a: (f32, f32), b: (f32, f32)| {
        painter.line_segment([point(a.0, a.1), point(b.0, b.1)], stroke);
    };
    match kind {
        Icon::Back => {
            line((-9., 0.), (8., 0.));
            line((-9., 0.), (-2., -7.));
            line((-9., 0.), (-2., 7.));
        }

        Icon::Info => {
            painter.circle_stroke(center, 9. * k, stroke);
            line((0., -1.), (0., 5.));
            painter.circle_filled(point(0., -5.), 1. * k, color);
        }
    }
}

fn icon_button(ui: &mut egui::Ui, kind: Icon, label: &str, p: Palette, enabled: bool) -> bool {
    let response = ui.add_enabled(
        enabled,
        egui::Button::new("").frame(false).min_size(vec2(48., 48.)),
    );
    response.widget_info(|| egui::WidgetInfo::labeled(egui::WidgetType::Button, enabled, label));
    let active = ui.ctx().animate_bool_with_time(
        response.id,
        response.is_pointer_button_down_on(),
        p.duration(),
    );
    ui.painter().circle_filled(
        response.rect.center(),
        22.,
        mix(p.surface, p.secondary, active),
    );
    ui.painter()
        .circle_stroke(response.rect.center(), 22., Stroke::new(1_f32, p.outline));
    icon(
        ui.painter(),
        response.rect.center(),
        kind,
        if enabled {
            p.muted
        } else {
            mix(p.surface, p.muted, 0.4)
        },
        22.,
    );
    response.on_hover_text(label).clicked()
}

fn mix(a: Color32, b: Color32, t: f32) -> Color32 {
    egui::lerp(egui::Rgba::from(a)..=egui::Rgba::from(b), t).into()
}

fn button(
    ui: &mut egui::Ui,
    label: &str,
    width: f32,
    primary: bool,
    enabled: bool,
    p: Palette,
) -> bool {
    ui.scope(|ui| {
        let (rect, response) = ui.allocate_exact_size(
            vec2(width, 52.),
            if enabled {
                egui::Sense::click()
            } else {
                egui::Sense::hover()
            },
        );
        response
            .widget_info(|| egui::WidgetInfo::labeled(egui::WidgetType::Button, enabled, label));
        let pressed = ui.ctx().animate_bool_with_time(
            response.id,
            response.is_pointer_button_down_on(),
            p.duration(),
        );
        let (fill, text) = if !enabled {
            (p.elevated, p.muted)
        } else if primary {
            (p.primary, p.on_primary)
        } else {
            (p.secondary, p.on_secondary)
        };
        let rect = rect.shrink(pressed * 1.5);
        ui.painter()
            .rect_filled(rect, 28., mix(fill, text, pressed * 0.12));
        ui.painter().text(
            rect.center(),
            egui::Align2::CENTER_CENTER,
            label,
            FontId::proportional(16.),
            text,
        );
        if !primary || !enabled {
            ui.painter().rect_stroke(
                rect,
                28.,
                Stroke::new(1_f32, p.outline),
                egui::StrokeKind::Inside,
            );
        }
        enabled && response.clicked()
    })
    .inner
}

fn card(ui: &mut egui::Ui, p: Palette, content: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::default()
        .fill(p.surface)
        .corner_radius(28)
        .inner_margin(20)
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            content(ui);
        });
}

#[derive(Clone, Copy)]
enum Dialog {
    Setup,
    Details,
    Restore,
    ConfirmApply,
}

struct VFontApp {
    preview: Option<egui::TextureHandle>,
    palette: Option<Palette>,
    tab_changed: f64,
    dialog: Option<Dialog>,
    notice: String,
    notice_until: f64,
}
impl VFontApp {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        shared().lock().unwrap().context = Some(cc.egui_ctx.clone());
        UI_GENERATION.fetch_add(1, Ordering::SeqCst);
        Self {
            preview: None,
            palette: None,
            tab_changed: -1.,
            dialog: None,
            notice: String::new(),
            notice_until: 0.,
        }
    }

    fn navigation(&mut self, ui: &mut egui::Ui, p: Palette) {
        ui.horizontal(|ui| {
            let (rect, _) = ui.allocate_exact_size(
                vec2((ui.available_width() - 66.).max(120.), 64.),
                egui::Sense::hover(),
            );
            ui.painter().rect_filled(rect, 34., p.primary);
            let back = egui::Rect::from_center_size(
                pos2(rect.left() + 32., rect.center().y),
                vec2(48., 48.),
            );
            ui.painter().circle_filled(back.center(), 24., p.surface);
            icon(ui.painter(), back.center(), Icon::Back, p.primary, 24.);
            let response = ui.interact(back, egui::Id::new("font-home"), egui::Sense::click());
            response.widget_info(|| {
                egui::WidgetInfo::labeled(egui::WidgetType::Button, true, "Back to tools")
            });
            if response.clicked() {
                android::action("home");
            }
            ui.painter().text(
                pos2(rect.center().x + 12., rect.center().y),
                egui::Align2::CENTER_CENTER,
                "Fonts",
                FontId::proportional(16.),
                p.on_primary,
            );
            if icon_button(ui, Icon::Info, "About fonts", p, true) {
                android::action("font_help");
            }
        });
    }
}

impl eframe::App for VFontApp {
    fn update(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        let (
            message,
            error,
            ready,
            supported,
            verified,
            experimental,
            busy,
            current,
            font,
            filename,
            pending_preview,
            p,
            insets,
            back,
        ) = {
            let mut state = shared().lock().unwrap();
            (
                state.message.clone(),
                state.error,
                state.ready,
                state.supported,
                state.command_verified,
                state.experimental,
                state.busy,
                state.current.clone(),
                state.font.clone(),
                state.filename.clone(),
                state.preview.take(),
                state.palette,
                state.insets,
                std::mem::take(&mut state.back),
            )
        };
        // Drop the state lock before touching egui; Android callbacks use both.
        if back || ctx.input(|input| input.key_pressed(egui::Key::Escape)) {
            if self.dialog.is_some() {
                self.dialog = None;
            } else if !busy {
                android::action("back");
            }
        }
        if self.palette != Some(p) {
            let mut style = (*ctx.style()).clone();
            style.visuals = if p.dark {
                egui::Visuals::dark()
            } else {
                egui::Visuals::light()
            };
            style.visuals.override_text_color = Some(p.text);
            style.visuals.panel_fill = p.background;
            style.visuals.selection.bg_fill = p.secondary;
            style.visuals.widgets.noninteractive.bg_stroke = Stroke::NONE;
            style.spacing.button_padding = vec2(16., 12.);
            style.spacing.item_spacing = vec2(10., 10.);
            style.animation_time = p.duration();
            for kind in [egui::TextStyle::Body, egui::TextStyle::Button] {
                style.text_styles.insert(kind, FontId::proportional(16.));
            }
            ctx.set_style(style);
            self.palette = Some(p);
        }
        if let Some((rgba, size)) = pending_preview {
            self.preview = Some(ctx.load_texture(
                "font-preview",
                egui::ColorImage::from_rgba_unmultiplied(size, &rgba),
                egui::TextureOptions::LINEAR,
            ));
        }
        if font.is_none() {
            self.preview = None;
        }
        let time = ctx.input(|i| i.time);
        if self.notice != message {
            self.notice = message.clone();
            self.notice_until = time + 5.;
        }
        let usable = ready && supported && verified;
        let applied = current
            .as_ref()
            .zip(font.as_ref())
            .is_some_and(|(active, selected)| active.sha256 == selected.sha256);
        let roboto = current
            .as_ref()
            .is_some_and(|active| active.target == crate::transaction::ROBOTO);

        let safe = insets.map(|px| px / ctx.pixels_per_point());
        let left = (20. + safe[0]).min(127.) as i8;
        let right = (20. + safe[2]).min(127.) as i8;
        egui::TopBottomPanel::top("status-bar-space")
            .exact_height(safe[1])
            .frame(egui::Frame::default().fill(p.background))
            .show(ctx, |_| {});
        egui::TopBottomPanel::bottom("navigation")
            .frame(
                egui::Frame::default()
                    .fill(p.background)
                    .inner_margin(egui::Margin {
                        left,
                        right,
                        top: 8,
                        bottom: (14. + safe[3]).min(127.) as i8,
                    }),
            )
            .show(ctx, |ui| self.navigation(ui, p));
        {
            egui::TopBottomPanel::bottom("font-actions")
                .frame(
                    egui::Frame::default()
                        .fill(p.background)
                        .inner_margin(egui::Margin {
                            left,
                            right,
                            top: 12,
                            bottom: 8,
                        }),
                )
                .show(ctx, |ui| {
                    ui.horizontal(|ui| {
                        let label = if busy {
                            "Working..."
                        } else if font.is_none() {
                            "Choose font"
                        } else if !ready {
                            "Connect Shizuku"
                        } else if !supported {
                            "Check setup"
                        } else if !verified {
                            "Test native access"
                        } else if applied {
                            "Choose another font"
                        } else {
                            "Apply font"
                        };
                        let width = ui.available_width() - 108.;
                        if button(ui, label, width, true, !busy, p) {
                            if font.is_none() || applied && usable {
                                android::action("pick");
                            } else if !ready {
                                android::action("connect");
                            } else if !supported {
                                self.dialog = Some(Dialog::Setup);
                            } else if !verified {
                                android::action("probe");
                            } else if experimental {
                                self.dialog = Some(Dialog::ConfirmApply);
                            } else {
                                android::action("apply");
                            }
                        }
                        if button(ui, "Restore", 98., false, usable && !busy && !roboto, p) {
                            self.dialog = Some(Dialog::Restore);
                        }
                    });
                });
        }
        egui::CentralPanel::default()
            .frame(
                egui::Frame::default()
                    .fill(p.background)
                    .inner_margin(egui::Margin {
                        left,
                        right,
                        top: 16,
                        bottom: 8,
                    }),
            )
            .show(ctx, |ui| {
                ui.add_space(12.);
                let progress = if p.motion {
                    ((time - self.tab_changed) / 0.22).clamp(0., 1.) as f32
                } else {
                    1.
                };
                if progress < 1. {
                    ctx.request_repaint();
                }
                egui::ScrollArea::vertical()
                    .id_salt("font-tool-page")
                    .scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden)
                    .animated(p.motion)
                    .show(ui, |ui| {
                        ui.set_opacity(0.3 + 0.7 * progress);
                        ui.add_space(8. * (1. - progress));
                        ui.label(RichText::new("Fonts").size(26.));
                        ui.label(
                            RichText::new(if applied {
                                "Your selected font is applied"
                            } else if !ready {
                                "Connect Shizuku to apply or restore"
                            } else if roboto {
                                "Currently using system default font"
                            } else {
                                "Preview a font before applying"
                            })
                            .color(p.muted)
                            .size(13.),
                        );
                        card(ui, p, |ui| {
                            if let Some(info) = &font {
                                ui.horizontal(|ui| {
                                    ui.set_height(48.);
                                    ui.label(
                                        RichText::new("Selected font").size(13.).color(p.muted),
                                    );
                                    ui.with_layout(
                                        egui::Layout::right_to_left(egui::Align::Center),
                                        |ui| {
                                            if icon_button(ui, Icon::Info, "Font details", p, true)
                                            {
                                                self.dialog = Some(Dialog::Details);
                                            }
                                        },
                                    );
                                });
                                ui.label(RichText::new(&info.family).size(24.));
                                ui.label(
                                    RichText::new(format!(
                                        "{} \u{b7} {:.0} KB",
                                        info.format,
                                        info.bytes as f32 / 1024.
                                    ))
                                    .size(13.)
                                    .color(p.muted),
                                );
                                if let Some(preview) = &self.preview {
                                    let ratio = preview.size_vec2().y / preview.size_vec2().x;
                                    let width = ui.available_width().min(180. / ratio);
                                    ui.add_space(4.);
                                    ui.add(
                                        egui::Image::new(preview)
                                            .fit_to_exact_size(vec2(width, width * ratio)),
                                    );
                                }
                                if info.has_emoji && !info.has_text {
                                    ui.label(
                                        RichText::new("Emoji only. Text will use system fallback.")
                                            .size(13.)
                                            .color(p.muted),
                                    );
                                }
                                ui.add_space(6.);
                                if !applied
                                    && button(
                                        ui,
                                        "Change font",
                                        ui.available_width(),
                                        false,
                                        !busy,
                                        p,
                                    )
                                {
                                    android::action("pick");
                                }
                            } else {
                                ui.add_space(28.);
                                ui.vertical_centered(|ui| {
                                    ui.label(RichText::new("Aa").size(84.).color(p.primary));
                                    ui.add_space(12.);
                                    ui.label(RichText::new("Text or emoji").size(22.));
                                    ui.label(
                                        RichText::new(
                                            "TTF \u{b7} OTF \u{b7} TTC \u{b7} WOFF / WOFF2",
                                        )
                                        .size(12.)
                                        .color(p.muted),
                                    );
                                });
                                ui.add_space(34.);
                            }
                        });
                        if !usable
                            && !busy
                            && button(
                                ui,
                                if ready && supported && !verified {
                                    "Finish setup \u{b7} test native access"
                                } else {
                                    "Connection & setup"
                                },
                                ui.available_width(),
                                false,
                                true,
                                p,
                            )
                        {
                            self.dialog = Some(Dialog::Setup);
                        }
                        // Routine status and preview-ready messages add noise. Keep errors,
                        // in-progress work and operation results visible at the point of use.
                        let routine = message.is_empty()
                            || (applied && message == "Font applied.")
                            || message == "Preview ready"
                            || message == "Selection removed"
                            || message.starts_with("Connected.")
                            || message.starts_with("Shizuku is running")
                            || message.starts_with("Font layout supported.");
                        let show_notice = busy || error || (!routine && time < self.notice_until);
                        if show_notice {
                            card(ui, p, |ui| {
                                ui.horizontal(|ui| {
                                    if busy {
                                        ui.spinner();
                                    }
                                    ui.add(
                                        egui::Label::new(
                                            RichText::new(&message)
                                                .color(if error { p.error } else { p.muted })
                                                .size(14.),
                                        )
                                        .wrap(),
                                    );
                                });
                            });
                            if !busy && !error {
                                ctx.request_repaint_after(Duration::from_secs_f64(
                                    (self.notice_until - time).max(0.01),
                                ));
                            }
                        }
                        if busy {
                            ctx.request_repaint_after(Duration::from_millis(100));
                        }
                        ui.add_space(8.);
                    });
            });
        if let Some(dialog) = self.dialog {
            let response = egui::Modal::new(egui::Id::new("details-dialog"))
                .frame(egui::Frame::default().fill(p.elevated).corner_radius(28).inner_margin(24))
                .show(ctx, |ui| {
                    ui.set_width((ctx.screen_rect().width() - 96.).clamp(180., 360.));
                    let title = match dialog {
                        Dialog::Setup => "Connection & setup",
                        Dialog::Details => "Font details",
                        Dialog::Restore => "Restore system default font?",
                        Dialog::ConfirmApply => "Apply font on untested software?",
                    };
                    ui.label(RichText::new(title).size(23.));
                    ui.add_space(12.);
                    egui::ScrollArea::vertical().max_height(ctx.screen_rect().height() * 0.56)
                        .scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden).show(ui, |ui| {
                        match dialog {
                            Dialog::Restore => {
                                ui.label("This replaces the active custom font with the system default font. Your imported file stays available in VFont. If VFont cannot restore, open Vivo Settings -> Display & brightness -> Font style (or i Theme -> Local).");
                                ui.add_space(12.);
                                if button(ui, "Restore system default font", ui.available_width(), true, usable && !busy, p) {android::action("restore");self.dialog = None;}
                            }
                            Dialog::ConfirmApply => {
                                ui.label("VFont has not been physically verified on this device or ROM. Applying replaces the active font without backing up custom i Theme fonts; Restore returns to the system default font.");
                                ui.add_space(8.);
                                ui.label(RichText::new("If text breaks or VFont cannot restore, revert in Vivo Settings -> Display & brightness -> Font style (or i Theme -> Local).").size(13.).color(p.muted));
                                ui.add_space(12.);
                                if button(ui, "Apply font", ui.available_width(), true, usable && !busy, p) {android::action("apply");self.dialog = None;}
                            }
                            Dialog::Setup => {
                                ui.label(RichText::new("Shizuku").size(18.));
                                ui.label(RichText::new(if ready {"Connected"} else {"Start Shizuku via Wireless debugging or ADB, then connect."}).color(p.muted).size(14.));
                                if button(ui, if ready {"Check connection"} else {"Connect"}, ui.available_width(), false, !busy, p) {android::action(if ready {"status"} else {"connect"});}
                                ui.add_space(8.);
                                ui.label(RichText::new(if verified {"Compatibility checked"} else {"Compatibility check"}).size(18.));
                                ui.label(RichText::new(if experimental {"Experimental on this software. The access test changes no font or icon, but the native service may restart. Then test one Apply and Restore."} else {"Read-only. No font changes."}).color(p.muted).size(14.));
                                if button(ui, "Test native access", ui.available_width(), false, ready && supported && !busy, p) {
                                    android::action("probe"); self.dialog = None;
                                }
                                if error {ui.label(RichText::new(&message).size(13.).color(p.error));}
                                ui.add_space(6.);
                                ui.label(RichText::new("If a font ever needs manual recovery, open Vivo Settings -> Display & brightness -> Font style (or i Theme -> Local).").size(12.).color(p.muted));
                                ui.add_space(8.);
                                if button(ui, "Device support & report", ui.available_width(), false, !busy, p) {
                                    android::action("support"); self.dialog = None;
                                }
                            }
                            Dialog::Details => {
                                if let Some(info) = &font {
                                    ui.label(RichText::new(&info.family).size(19.));
                                    ui.label(RichText::new(&filename).size(13.).color(p.muted));
                                    ui.label(format!("{} \u{b7} {} \u{b7} {} glyphs", info.style, info.format, info.glyphs));
                                    if info.variable {ui.label("Variable \u{b7} default axes");}
                                    if !info.color_formats.is_empty() {ui.label(format!("Color: {}", info.color_formats.join(", ")));}
                                    ui.add_space(8.);
                                    ui.label(RichText::new("Preview includes system fallback. Apps may use their own fonts or emoji.").size(13.).color(p.muted));
                                    ui.add_space(12.);
                                    if button(ui, "Remove selected file", ui.available_width(), false, !busy, p) {android::action("clear");self.dialog = None;}
                                }
                            }
                        }
                    });
                    ui.add_space(12.);
                    let cancelable = matches!(dialog, Dialog::Restore | Dialog::ConfirmApply);
                    if button(ui, if cancelable { "Cancel" } else { "Done" }, ui.available_width(), !cancelable, true, p) {self.dialog = None;}
                });
            if response.should_close() {
                self.dialog = None;
            }
        }
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
