from collections import OrderedDict, deque

from .errors import StorageError


class ReplacementPolicy:
    name = "BASE"

    def record_insert(self, page_id: int):
        raise NotImplementedError

    def record_access(self, page_id: int):
        raise NotImplementedError

    def record_remove(self, page_id: int):
        raise NotImplementedError

    def choose_victim(self, resident_page_ids):
        raise NotImplementedError


class LRUPolicy(ReplacementPolicy):
    name = "LRU"

    def __init__(self):
        self._order = OrderedDict()

    def record_insert(self, page_id: int):
        self._order.pop(page_id, None)
        self._order[page_id] = None

    def record_access(self, page_id: int):
        if page_id not in self._order:
            raise StorageError("BUFFER_POLICY_INVALID_STATE", "LRU 访问了未登记页", page_id=page_id)
        self._order.move_to_end(page_id)

    def record_remove(self, page_id: int):
        self._order.pop(page_id, None)

    def choose_victim(self, resident_page_ids):
        residents = list(resident_page_ids)
        if not residents or len(residents) != len(set(residents)):
            raise StorageError("BUFFER_POLICY_INVALID_STATE", "候选页集合无效")
        resident_set = set(residents)
        for page_id in self._order:
            if page_id in resident_set:
                return page_id
        raise StorageError("BUFFER_POLICY_INVALID_STATE", "候选页未在 LRU 策略中登记")


class FIFOPolicy(ReplacementPolicy):
    name = "FIFO"

    def __init__(self):
        self._queue = deque()
        self._known = set()

    def record_insert(self, page_id: int):
        if page_id in self._known:
            return
        self._known.add(page_id)
        self._queue.append(page_id)

    def record_access(self, page_id: int):
        if page_id not in self._known:
            raise StorageError("BUFFER_POLICY_INVALID_STATE", "FIFO 访问了未登记页", page_id=page_id)

    def record_remove(self, page_id: int):
        if page_id not in self._known:
            return
        self._known.remove(page_id)
        self._queue = deque(v for v in self._queue if v != page_id)

    def choose_victim(self, resident_page_ids):
        residents = list(resident_page_ids)
        if not residents or len(residents) != len(set(residents)):
            raise StorageError("BUFFER_POLICY_INVALID_STATE", "候选页集合无效")
        resident_set = set(residents)
        for page_id in self._queue:
            if page_id in resident_set:
                return page_id
        raise StorageError("BUFFER_POLICY_INVALID_STATE", "候选页未在 FIFO 策略中登记")


def make_policy(name: str) -> ReplacementPolicy:
    normalized = str(name).upper()
    if normalized == "LRU":
        return LRUPolicy()
    if normalized == "FIFO":
        return FIFOPolicy()
    raise StorageError("BUFFER_POLICY_INVALID", "policy 只支持 LRU 或 FIFO")
