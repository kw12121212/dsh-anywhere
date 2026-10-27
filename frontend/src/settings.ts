import {Service as ConfigService} from "../bindings/dsh-anywhere/internal/config";
import {Service as SettingsService} from "../bindings/dsh-anywhere/internal/settings";
import {bindValidation, errorMessage} from "./validation";

const placeholder = document.getElementById('placeholder') as HTMLElement;
const section = document.getElementById('settings') as HTMLElement;
const form = document.getElementById('settings-form') as HTMLFormElement;
const input = document.getElementById('settings-url') as HTMLInputElement;
const errorText = document.getElementById('settings-error') as HTMLParagraphElement;
const saveButton = document.getElementById('save') as HTMLButtonElement;
const cancelButton = document.getElementById('cancel') as HTMLButtonElement;

export async function startSettings(): Promise<void> {
    document.title = '设置 - dsh-anywhere';
    // 先读当前地址：失败时占位区仍可见，启动错误能显示出来。
    const current = await ConfigService.CurrentURL();
    placeholder.hidden = true;
    section.hidden = false;

    const validation = bindValidation(input, errorText, saveButton);
    input.value = current;
    await validation.validate();

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        if (saveButton.disabled) return;
        validation.invalidate();
        saveButton.disabled = true;
        input.disabled = true;
        errorText.textContent = '';
        try {
            // 成功后 Go 侧关闭设置窗口，并在需要时让主窗口打开新地址。
            await SettingsService.Save(input.value);
        } catch (err) {
            input.disabled = false;
            await validation.validate();
            errorText.textContent = errorMessage(err);
        }
    });
    cancelButton.addEventListener('click', () => {
        SettingsService.Cancel().catch((err) => {
            errorText.textContent = errorMessage(err);
        });
    });
    input.focus();
}
