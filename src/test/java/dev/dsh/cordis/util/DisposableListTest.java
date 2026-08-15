package dev.dsh.cordis.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DisposableListTest {
    @Test
    void clearReturnsReverseOrder() {
        DisposableList<String> list = new DisposableList<>();
        list.push("a"); list.push("b"); list.push("c");
        assertThat(list.clear()).containsExactly("c", "b", "a");
        assertThat(list.length()).isZero();
    }

    @Test
    void deleteByValue() {
        DisposableList<String> list = new DisposableList<>();
        list.push("a");
        Runnable remove = list.push("b");
        assertThat(list.delete("a")).isTrue();
        assertThat(list.length()).isEqualTo(1);
        remove.run(); // remover also works
        assertThat(list.length()).isZero();
    }
}
