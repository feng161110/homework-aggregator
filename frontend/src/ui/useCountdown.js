import { onUnmounted, ref } from 'vue';

/** 重新发送倒计时（与后端 email-send-interval-seconds 保持一致） */
export function useCountdown() {
    const left = ref(0);
    let timer = null;

    function stop() {
        if (timer) clearInterval(timer);
        timer = null;
    }

    function start(seconds) {
        stop();
        left.value = seconds;
        timer = setInterval(() => {
            left.value -= 1;
            if (left.value <= 0) stop();
        }, 1000);
    }

    onUnmounted(stop);

    return { left, start, stop };
}
