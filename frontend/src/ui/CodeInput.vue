<script setup>
import { nextTick, onMounted, ref, watch } from 'vue';

const props = defineProps({
    modelValue: { type: String, default: '' },
    length: { type: Number, default: 6 },
    autofocus: { type: Boolean, default: true },
});

const emit = defineEmits(['update:modelValue', 'complete']);

const digits = ref(Array.from({ length: props.length }, (_, i) => props.modelValue[i] || ''));
const cells = ref([]);
const shaking = ref(false);

onMounted(() => {
    if (props.autofocus) nextTick(() => cells.value[0]?.focus());
});

watch(() => props.modelValue, (value) => {
    const next = Array.from({ length: props.length }, (_, i) => value[i] || '');
    if (next.join('') !== digits.value.join('')) digits.value = next;
});

function emitValue() {
    const code = digits.value.join('');
    emit('update:modelValue', code);
    if (code.length === props.length) emit('complete', code);
}

function focusCell(index) {
    const i = Math.max(0, Math.min(props.length - 1, index));
    cells.value[i]?.focus();
}

function onInput(index, event) {
    const value = event.target.value.replace(/\D/g, '').slice(-1);
    event.target.value = value;
    digits.value[index] = value;
    if (value && index < props.length - 1) focusCell(index + 1);
    emitValue();
}

function onKeydown(index, event) {
    if (event.key === 'Backspace' && !digits.value[index] && index > 0) focusCell(index - 1);
    if (event.key === 'ArrowLeft') focusCell(index - 1);
    if (event.key === 'ArrowRight') focusCell(index + 1);
}

function onPaste(event) {
    event.preventDefault();
    const text = (event.clipboardData?.getData('text') || '').replace(/\D/g, '').slice(0, props.length);
    if (!text) return;
    // 先清空再填：否则手动输过几位后粘贴短串，会拼出「旧尾 + 新头」的脏验证码
    digits.value = Array.from({ length: props.length }, (_, i) => text[i] || '');
    focusCell(text.length - 1);
    nextTick(emitValue);
}

/** 校验失败时抖动 */
function markInvalid() {
    shaking.value = true;
    setTimeout(() => { shaking.value = false; }, 450);
}

function clear() {
    digits.value = Array.from({ length: props.length }, () => '');
    emit('update:modelValue', '');
    focusCell(0);
}

defineExpose({ markInvalid, clear, focus: () => focusCell(0) });
</script>

<template>
    <div class="code-inputs" @paste="onPaste">
        <input v-for="(digit, i) in digits" :key="i"
               ref="cells"
               class="code-cell"
               :class="{ filled: Boolean(digit), invalid: shaking }"
               inputmode="numeric"
               maxlength="1"
               autocomplete="one-time-code"
               :value="digit"
               @input="onInput(i, $event)"
               @keydown="onKeydown(i, $event)" />
    </div>
</template>
