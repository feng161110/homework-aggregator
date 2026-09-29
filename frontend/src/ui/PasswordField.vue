<script setup>
import { computed, ref } from 'vue';
import { icons } from './icons';
import { passwordStrength } from './validate';

const props = defineProps({
    modelValue: { type: String, default: '' },
    placeholder: { type: String, default: '密码' },
    autocomplete: { type: String, default: 'current-password' },
    withStrength: { type: Boolean, default: false },
    invalid: { type: Boolean, default: false },
    /** 第三方平台凭证字段：禁止浏览器保存/回填 */
    harden: { type: Boolean, default: false },
});

const emit = defineEmits(['update:modelValue']);

const el = ref(null);
const shown = ref(false);
const locked = ref(props.harden);

const fieldName = props.harden
    ? `pwd-${Math.random().toString(36).slice(2, 10)}`
    : undefined;
const autocompleteValue = computed(() => (props.harden ? 'new-password' : props.autocomplete));
const level = computed(() => passwordStrength(props.modelValue));

function unlock() {
    locked.value = false;
}

defineExpose({ focus: () => el.value?.focus() });
</script>

<template>
    <div class="control has-suffix">
        <span class="icon" v-html="icons.lock"></span>
        <input ref="el"
               :value="modelValue"
               :type="shown ? 'text' : 'password'"
               :name="fieldName"
               :placeholder="placeholder"
               :autocomplete="autocompleteValue"
               :readonly="locked"
               :class="{ invalid }"
               @input="emit('update:modelValue', $event.target.value)"
               @focus="unlock"
               @pointerdown="unlock" />
        <span class="suffix">
            <button type="button" class="toggle-eye"
                    :aria-label="shown ? '隐藏密码' : '显示密码'"
                    @click="shown = !shown" v-html="shown ? icons.eyeOff : icons.eye"></button>
        </span>
    </div>
    <div v-if="withStrength" class="strength" :data-level="level"><i></i><i></i><i></i><i></i></div>
</template>
