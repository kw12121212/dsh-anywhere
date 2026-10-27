import {Service as MainWindowService} from "../bindings/dsh-anywhere/internal/mainwindow";
import {Service as SettingsService} from "../bindings/dsh-anywhere/internal/settings";
import {errorMessage} from "./validation";

const placeholder = document.getElementById('placeholder') as HTMLElement;
const section = document.getElementById('load-failed') as HTMLElement;
const urlText = document.getElementById('failed-url') as HTMLElement;
const reasonText = document.getElementById('failed-reason') as HTMLParagraphElement;
const errorText = document.getElementById('failed-error') as HTMLParagraphElement;
const retryButton = document.getElementById('retry') as HTMLButtonElement;
const changeButton = document.getElementById('change-url') as HTMLButtonElement;

const reasons: Record<string, string> = {
    dns: '无法解析主机名，请检查地址是否正确以及网络（如 Tailscale）是否已连接。',
    refused: '连接被拒绝：主机上的 dsh web 可能没有运行，或端口不正确。',
    timeout: '连接超时：主机可能已关机或网络不可达。',
    tls: '安全连接（TLS）失败：证书不受信任或与主机名不符。',
};

export async function startLoadFailed(): Promise<void> {
    document.title = '无法连接 - dsh-anywhere';
    const params = new URLSearchParams(location.search);
    urlText.textContent = params.get('url') ?? '';
    reasonText.textContent = reasons[params.get('reason') ?? ''] ?? '无法连接到该地址。';
    placeholder.hidden = true;
    section.hidden = false;

    retryButton.addEventListener('click', () => {
        errorText.textContent = '';
        retryButton.disabled = true;
        reasonText.textContent = '正在重新连接…';
        // 成功时 Go 侧把主窗口导航到 harness，失败时重新打开本页。
        MainWindowService.Retry().catch((err) => {
            retryButton.disabled = false;
            errorText.textContent = errorMessage(err);
        });
    });
    changeButton.addEventListener('click', () => {
        errorText.textContent = '';
        SettingsService.Open().catch((err) => {
            errorText.textContent = errorMessage(err);
        });
    });
    retryButton.focus();
}

export async function startConnecting(): Promise<void> {
    const statusText = document.getElementById('status') as HTMLParagraphElement;
    statusText.textContent = '正在连接 harness…';
}
