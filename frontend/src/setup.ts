import {Service as SetupService} from "../bindings/dsh-anywhere/internal/setup";
import {bindValidation, errorMessage} from "./validation";

const placeholder = document.getElementById('placeholder') as HTMLElement;
const statusText = document.getElementById('status') as HTMLParagraphElement;
const setupSection = document.getElementById('setup') as HTMLElement;
const invalidNotice = document.getElementById('invalid-config') as HTMLParagraphElement;
const form = document.getElementById('setup-form') as HTMLFormElement;
const input = document.getElementById('harness-url') as HTMLInputElement;
const errorText = document.getElementById('url-error') as HTMLParagraphElement;
const startButton = document.getElementById('start') as HTMLButtonElement;

export async function startSetup(): Promise<void> {
    const state = await SetupService.State();
    if (!state.needsSetup) {
        statusText.textContent = '已保存 harness 地址。';
        return;
    }
    placeholder.hidden = true;
    setupSection.hidden = false;
    invalidNotice.hidden = !state.invalidConfig;

    const validation = bindValidation(input, errorText, startButton);
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        if (startButton.disabled) return;
        validation.invalidate();
        startButton.disabled = true;
        input.disabled = true;
        errorText.textContent = '';
        try {
            // 成功后由 Go 侧把主窗口导航到 harness 页面。
            await SetupService.Complete(input.value);
            statusText.textContent = '正在打开 harness…';
            setupSection.hidden = true;
            placeholder.hidden = false;
        } catch (err) {
            input.disabled = false;
            // 按当前输入重新校验决定按钮状态，再显示本次提交的错误。
            await validation.validate();
            errorText.textContent = errorMessage(err);
        }
    });
    input.focus();
}

export function showStartupError(err: unknown): void {
    statusText.textContent = `启动失败：${errorMessage(err)}`;
}
