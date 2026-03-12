# BotDrop Android GA4 Event Dictionary

Date: 2026-03-12
Application ID: `app.botdrop`
Analytics backend: Firebase Analytics / GA4

## Scope

This document covers the BotDrop Android UI instrumentation added in the launcher, setup funnel, dashboard, automation panel, and the integrated Shizuku `manager` flows.

Sensitive values are intentionally excluded from analytics payloads:

- API keys
- bot tokens
- owner IDs
- guild IDs
- user IDs
- raw stderr/stdout

Allowed dimensions are limited to low-cardinality values such as:

- `step`
- `platform`
- `provider`
- `reason`

## Screen views

| Event | Trigger |
| --- | --- |
| `screen_view` with `screen_name=launcher_welcome` | Launcher welcome/permission screen becomes visible |
| `screen_view` with `screen_name=launcher_loading` | Launcher loading/routing screen becomes visible |
| `screen_view` with `screen_name=setup_agent_select` | Setup step: agent selection |
| `screen_view` with `screen_name=setup_install` | Setup step: install |
| `screen_view` with `screen_name=setup_auth` | Setup step: auth/provider setup |
| `screen_view` with `screen_name=setup_channel` | Setup step: channel setup |
| `screen_view` with `screen_name=dashboard_main` | Main dashboard resumes |
| `screen_view` with `screen_name=automation_panel` | Automation panel resumes |
| `screen_view` with `screen_name=automation_shizuku_status` | Fallback internal Shizuku status screen resumes |
| `screen_view` with `screen_name=automation_shizuku_pair_tutorial` | Integrated Shizuku pairing tutorial screen resumes |
| `screen_view` with `screen_name=automation_shizuku_starter` | Integrated Shizuku starter screen resumes |
| `screen_view` with `screen_name=automation_shizuku_permission_request` | Integrated Shizuku permission confirmation screen resumes |

## Launcher events

| Event | Params | Trigger |
| --- | --- | --- |
| `launcher_notification_tap` | none | Tap notification settings |
| `launcher_battery_tap` | none | Tap battery optimization settings |
| `launcher_background_tap` | none | Tap background settings |
| `launcher_update_check_tap` | none | Tap manual app update check |
| `launcher_continue_tap` | none | Tap continue from launcher welcome |

## Setup funnel events

| Event | Params | Trigger |
| --- | --- | --- |
| `setup_terminal_tap` | `step` | Open terminal from setup |
| `setup_back_tap` | `step` | Tap back in setup nav |
| `setup_next_tap` | `step` | Tap next in setup nav |
| `setup_update_check_tap` | `step` | Tap app update check in setup |
| `setup_update_open_browser` | none | Open browser from setup update dialog |
| `setup_restore_prompt` | none | Restore backup prompt shown after install |
| `setup_restore_accept` | none | User accepts restore |
| `setup_restore_skip` | none | User skips restore |
| `setup_restore_completed` | none | Restore succeeded |
| `setup_restore_failed` | none | Restore failed |
| `setup_complete` | none | Setup flow completed and dashboard launched |

## Agent selection events

| Event | Params | Trigger |
| --- | --- | --- |
| `agent_install_tap` | none | Select install from agent page |
| `agent_open_dashboard_tap` | none | Open dashboard when OpenClaw already installed |
| `agent_version_manager_tap` | none | Open version manager |
| `agent_version_install_started` | none | Start in-place OpenClaw install/update from version manager |
| `agent_version_install_failed` | none | In-place OpenClaw install/update failed from version manager |
| `agent_version_install_completed` | none | In-place OpenClaw install/update completed from version manager |
| `agent_openclaw_link_tap` | none | Open OpenClaw website |

## Install events

| Event | Params | Trigger |
| --- | --- | --- |
| `install_started` | none | OpenClaw install begins |
| `install_completed` | none | OpenClaw install finishes successfully |
| `install_failed` | none | OpenClaw install callback fails |
| `install_retry_tap` | none | Tap retry after install error |

## Auth events

| Event | Params | Trigger |
| --- | --- | --- |
| `auth_model_select_tap` | none | Open model selector |
| `auth_key_toggle_tap` | none | Toggle API key visibility |
| `auth_verify_tap` | none | Tap verify button |
| `auth_model_selected` | `provider` | Model selection completed |
| `auth_verify_success` | `provider` | Credentials/config saved successfully |
| `auth_verify_failed` | `reason` | Validation or save failed |

Known `reason` values:

- `missing_model`
- `base_url_changed`
- `missing_base_url`
- `missing_api_key`
- `invalid_api_key_format`
- `missing_custom_model_list`
- `write_config_failed`

## Channel events

| Event | Params | Trigger |
| --- | --- | --- |
| `channel_tab_view` | `platform` | Channel tab becomes active |
| `channel_setup_bot_tap` | `platform` | Open platform bot setup link |
| `channel_connect_tap` | `platform` | Tap connect |
| `channel_connect_saved` | `platform` | Channel config saved locally |
| `channel_connect_failed` | `platform` | Channel config write failed |
| `channel_connect_invalid` | `reason` | Channel validation failed |
| `channel_gateway_started` | `platform` | Gateway start succeeded after channel setup |
| `channel_gateway_failed` | `platform` | Gateway start failed or service unavailable |
| `channel_skip_confirmed` | `platform` | Confirm skip channel setup |
| `channel_skip_cancelled` | `platform` | Cancel skip dialog |
| `channel_finish_existing` | `platform` | Close editing flow when config already exists |

Known `platform` values:

- `telegram`
- `discord`
- `feishu`
- `qqbot`

Known `reason` values:

- `invalid_token`
- `invalid_owner`
- `invalid_guild`

## Dashboard events

| Event | Params | Trigger |
| --- | --- | --- |
| `dashboard_start_tap` | none | Tap start gateway |
| `dashboard_start_success` | none | Gateway start succeeds |
| `dashboard_start_failed` | none | Gateway start fails |
| `dashboard_stop_tap` | none | Tap stop gateway |
| `dashboard_stop_success` | none | Gateway stop succeeds |
| `dashboard_stop_failed` | none | Gateway stop fails |
| `dashboard_restart_tap` | none | Tap restart gateway |
| `dashboard_restart_success` | none | Gateway restart succeeds |
| `dashboard_restart_failed` | none | Gateway restart fails |
| `dashboard_terminal_tap` | none | Open terminal |
| `dashboard_model_tap` | none | Open model selector |
| `dashboard_channel_tap` | `platform` | Open channel config from dashboard |
| `dashboard_automation_tap` | none | Open automation panel |
| `dashboard_agent_select_tap` | none | Return to agent selection |

## App update events

| Event | Params | Trigger |
| --- | --- | --- |
| `app_update_available` | none | App update callback reports update |
| `app_update_none` | none | App update callback reports no update |
| `app_update_banner_shown` | none | Dashboard update banner displayed |
| `app_update_download_tap` | none | Tap download from app update banner |
| `app_update_dismiss_tap` | none | Dismiss app update banner |

## OpenClaw maintenance events

| Event | Params | Trigger |
| --- | --- | --- |
| `openclaw_update_check_tap` | none | Tap manual OpenClaw update check |
| `openclaw_update_check_blocked` | none | Manual OpenClaw update check attempted while service unavailable |
| `openclaw_update_available_auto` | none | Background/throttled OpenClaw update check found update |
| `openclaw_update_none_auto` | none | Background/throttled OpenClaw update check found no update |
| `openclaw_update_available_manual` | none | Manual OpenClaw update check found update |
| `openclaw_update_none_manual` | none | Manual OpenClaw update check found no update |
| `openclaw_update_dialog_shown` | `source` | OpenClaw update dialog displayed |
| `openclaw_update_accept_tap` | `source` | Confirm OpenClaw update from dialog |
| `openclaw_update_later_tap` | `source` | Choose later from OpenClaw update dialog |
| `openclaw_update_dismiss_tap` | `source` | Dismiss OpenClaw update permanently from dialog |
| `openclaw_update_started` | none | Start OpenClaw update progress flow |
| `openclaw_update_failed` | none | OpenClaw update progress flow failed |
| `openclaw_update_completed` | none | OpenClaw update progress flow completed |
| `openclaw_log_tap` | none | Open OpenClaw log viewer |
| `openclaw_webui_tap` | none | Open OpenClaw Web UI |
| `openclaw_backup_tap` | none | Start OpenClaw backup flow |
| `openclaw_restore_tap` | none | Start OpenClaw restore flow |

Known `source` values:

- `auto`
- `manual`

## Automation events

| Event | Params | Trigger |
| --- | --- | --- |
| `automation_back_tap` | none | Leave automation panel |
| `automation_open_shizuku_tap` | none | Open Shizuku UI |
| `automation_permission_tap` | none | Start Shizuku permission diagnosis/request |
| `automation_u2_start_tap` | none | Start U2 service |
| `automation_u2_start_started` | none | U2 startup progress dialog shown |
| `automation_u2_start_completed` | none | U2 startup flow completed |
| `automation_u2_start_failed` | `reason` | U2 startup flow failed |
| `automation_u2_stop_tap` | none | Stop U2 service |
| `automation_u2_stop_started` | none | U2 stop flow started |
| `automation_u2_stop_completed` | none | U2 stop command succeeded |
| `automation_u2_stop_failed` | none | U2 stop command failed |
| `automation_shizuku_status` | via `screen_view` | Fallback internal Shizuku status screen shown |
| `automation_shizuku_auto_flow_start` | none | Fallback internal Shizuku auto bootstrap/request flow begins |
| `automation_shizuku_refresh_tap` | none | Tap refresh on fallback internal Shizuku status screen |
| `automation_shizuku_bootstrap_tap` | none | Tap bootstrap on fallback internal Shizuku status screen |
| `automation_shizuku_permission_tap` | none | Tap request permission on fallback internal Shizuku status screen |
| `automation_shizuku_permission_blocked` | `reason` | Fallback internal Shizuku permission request blocked |
| `automation_shizuku_permission_already_granted` | none | Fallback internal Shizuku permission already granted |
| `automation_shizuku_permission_granted` | `mode` | Integrated Shizuku permission granted |
| `automation_shizuku_permission_denied` | `mode` | Integrated Shizuku permission denied |
| `automation_shizuku_permission_dialog_shown` | none | Integrated Shizuku permission dialog shown |
| `automation_shizuku_permission_limited_dialog_shown` | none | Integrated Shizuku permission-limited dialog shown |
| `automation_shizuku_permission_flow_failed` | `reason` | Integrated Shizuku permission flow failed before user action |
| `automation_shizuku_settings_tap` | none | Open app permission settings from fallback internal Shizuku status screen |
| `automation_shizuku_pair_tap` | none | Tap integrated Shizuku pair entry |
| `automation_shizuku_pair_dialog_shown` | none | Pairing dialog shown in multi-display/dialog path |
| `automation_shizuku_pair_tutorial` | via `screen_view` | Pairing tutorial screen shown |
| `automation_shizuku_pair_search_started` | none | Pairing service/search started |
| `automation_shizuku_pair_settings_tap` | none | Open developer settings during pairing flow |
| `automation_shizuku_pair_notification_settings_tap` | none | Open notification settings during pairing tutorial |
| `automation_shizuku_pair_submit` | none | Submit pairing code |
| `automation_shizuku_pair_completed` | none | Pairing succeeded |
| `automation_shizuku_pair_failed` | `reason` | Pairing failed |
| `automation_shizuku_start_tap` | optional `source` | Tap integrated Shizuku start entry |
| `automation_shizuku_start_dialog_shown` | none | Wireless start dialog shown |
| `automation_shizuku_start_submit` | none | Submit wireless start dialog |
| `automation_shizuku_starter` | via `screen_view` | Integrated starter screen shown |
| `automation_shizuku_start_started` | `source` | Integrated Shizuku start flow started |
| `automation_shizuku_start_completed` | `source` | Integrated Shizuku start flow completed |
| `automation_shizuku_start_failed` | `reason` | Integrated Shizuku start flow failed |
| `automation_shizuku_computer_start_tap` | none | Tap “start via computer” card |
| `automation_shizuku_computer_start_dialog_shown` | none | “start via computer” command dialog shown |
| `automation_shizuku_computer_start_copy_tap` | none | Copy adb command from “start via computer” dialog |
| `automation_shizuku_computer_start_send_tap` | none | Share adb command from “start via computer” dialog |
| `automation_shizuku_stop_menu_tap` | none | Tap “Stop Shizuku” menu item |
| `automation_shizuku_stop_blocked` | `reason` | Stop action blocked before dialog |
| `automation_shizuku_stop_dialog_shown` | none | Stop confirmation dialog shown |
| `automation_shizuku_stop_confirm_tap` | none | Confirm stop Shizuku |
| `automation_shizuku_stop_cancel_tap` | none | Cancel stop Shizuku |
| `automation_shizuku_stop_completed` | none | Stop Shizuku completed |
| `automation_shizuku_stop_failed` | none | Stop Shizuku threw/failed |

Known `reason` values for `automation_u2_start_failed`:

- `service_not_connected`
- `copy_u2_failed`
- `prepare_env_failed`
- `install_u2automator_failed`
- `install_ime_failed`
- `enable_ime_failed`
- `start_process_failed`
- `unknown`

Known `mode` values:

- `always`
- `onetime`
- `limited`

Known `source` values for integrated Shizuku start:

- `wireless`
- `root`

Known `reason` values for `automation_shizuku_pair_failed`:

- `connect`
- `invalid_code`
- `key_store`
- `unknown`

Known `reason` values for `automation_shizuku_start_failed`:

- `key_store`
- `not_rooted`
- `connect`
- `pair_required`
- `unknown`

Known `reason` values for `automation_shizuku_permission_flow_failed`:

- `binder_timeout`
- `invalid_request`

Known `reason` values for `automation_shizuku_permission_blocked` and `automation_shizuku_stop_blocked`:

- `binder_not_ready`

## Recommended GA4 reports

1. Setup funnel:
   `launcher_continue_tap` -> `screen_view/setup_agent_select` -> `install_completed` -> `auth_verify_success` -> `channel_gateway_started` -> `setup_complete`
2. Auth failure breakdown:
   filter `auth_verify_failed` by `reason`
3. Channel setup quality:
   compare `channel_connect_saved` vs `channel_gateway_failed`
4. Gateway control reliability:
   compare start/stop/restart tap events vs success/failure events
5. Update intent:
   compare update check taps vs available/none results
6. Automation reliability:
   compare `automation_u2_start_*`, `automation_u2_stop_*`, `automation_shizuku_start_*`, and `automation_shizuku_pair_*`
7. Shizuku permission outcomes:
   compare `automation_permission_tap` vs `automation_shizuku_permission_granted/denied`

## DebugView checklist

Use GA4 DebugView before trusting any dashboard numbers.

### 1. Enable Analytics debug mode on the device

```bash
adb shell setprop debug.firebase.analytics.app app.botdrop
```

To disable it later:

```bash
adb shell setprop debug.firebase.analytics.app .none.
```

### 2. Open Firebase DebugView

Firebase Console -> Analytics -> DebugView

### 3. Execute the recommended manual path on device

1. Launch app and confirm `launcher_welcome`
2. Tap Continue and confirm `launcher_continue_tap`
3. Walk through setup and confirm `setup_*` screen views
4. Install OpenClaw and confirm `install_started` then `install_completed`
5. Complete auth and confirm `auth_verify_tap` then `auth_verify_success`
6. Configure one channel and confirm `channel_connect_saved` and `channel_gateway_started`
7. Reach dashboard and confirm `dashboard_main`
8. Tap Start/Stop/Restart and confirm paired success/failure result events
9. Tap OpenClaw update check and confirm one of the `openclaw_update_*` result events
10. Open Automation Panel and confirm `automation_panel`
11. Run at least one Shizuku path and confirm one of:
    `automation_shizuku_pair_*`, `automation_shizuku_start_*`, `automation_shizuku_stop_*`
12. Run U2 start/stop and confirm `automation_u2_*` events

### 4. Sanity checks

1. No secret-like values should appear in event params
2. `provider`, `platform`, `step`, and `reason` values should stay low-cardinality
3. Success/failure pairs should never both fire for the same single callback path

### 5. If DebugView shows nothing

1. Confirm the app uses the `app.botdrop` package on device
2. Confirm `google-services.json` is present in `app/`
3. Confirm analytics collection is enabled in app settings/debug preferences
4. Relaunch the app after running the `adb shell setprop` command
