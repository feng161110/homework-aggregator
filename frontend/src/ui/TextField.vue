<script setup>
import { ref } from 'vue';

const props = defineProps({
    modelValue: { type: String, default: '' },
    type: { type: String, default: 'text' },
    placeholder: { type: String, default: '' },
    icon: { type: String, default: '' },
    autocomplete: { type: String, default: undefined },
    invalid: { type: Boolean, default: false },
    /** 第三方平台凭证字段：禁止浏览器保存/回填 */
    harden: { type: Boolean, default: false },
});

const emit = defineEmits(['update:modelValue']);

const el = ref(null);
const locked = ref(props.harden);
const fieldName = props.harden
    ? `acct-${Math.random().toString(36).slice(2, 10)}`
    : undefined;

function unlock() {
    locked.value = false;
}

defineExpose({ focus: () => el.value?.focus() });
</script>

<template>
    <div class="control">
        <span v-if="icon" class="icon" v-html="icon"></span>
        <input ref="el"
               :value="modelValue"
               :type="type"
               :name="fieldName"
               :placeholder="placeholder"
               :autocomplete="harden ? 'off' : autocomplete"
               :readonly="locked"
               :class="{ invalid }"
               @input="emit('update:modelValue', $event.target.value)"
               @focus="unlock"
               @pointerdown="unlock" />
    </div>
</template>
