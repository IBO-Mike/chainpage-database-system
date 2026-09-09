from collections import OrderedDict, deque

from .errors import StorageError


def _page_id(page_id):
    if not isinstance(page_id, int) or isinstance(page_id, bool) or page_id < 0:
        raise StorageError('INVALID_PAGE_ID', 'pageId 必须是非负整数', page_id=page_id)
    return page_id


class ReplacementPolicy:
    name = 'BASE'

    def record_insert(self, page_id: int):
        raise NotImplementedError

    def record_access(self, page_id: int):
        raise NotImplementedError

    def record_remove(self, page_id: int):
        raise NotImplementedError

    def choose_victim(self, resident_page_ids):
        raise NotImplementedError

    @staticmethod
    def _fields(request, allowed):
        unknown = set(request) - set(allowed)
        if unknown:
            raise StorageError('INVALID_REQUEST', f'请求包含未定义字段: {sorted(unknown)}')

    def handle(self, request):
        try:
            if not isinstance(request, dict):
                raise StorageError('INVALID_REQUEST', '请求必须是 JSON 对象')
            op = request.get('op')
            if op == 'record_insert':
                self._fields(request, {'op', 'pageId'}); self.record_insert(request.get('pageId')); result = {'recorded': True}
            elif op == 'record_access':
                self._fields(request, {'op', 'pageId'}); self.record_access(request.get('pageId')); result = {'recorded': True}
            elif op == 'choose_victim':
                self._fields(request, {'op', 'residentPageIds'})
                residents = request.get('residentPageIds')
                if not isinstance(residents, list):
                    raise StorageError('BUFFER_POLICY_INVALID_STATE', 'residentPageIds 必须是数组')
                result = {'pageId': self.choose_victim(residents)}
            else:
                raise StorageError('UNSUPPORTED_OPERATION', f'不支持的 Replacement Policy 操作: {op}')
            return {'ok': True, 'data': result}
        except StorageError as exc:
            return {'ok': False, 'error': exc.to_dict()}


class LRUPolicy(ReplacementPolicy):
    name = 'LRU'

    def __init__(self):
        self._order = OrderedDict()

    def record_insert(self, page_id: int):
        page_id = _page_id(page_id)
        self._order.pop(page_id, None)
        self._order[page_id] = None

    def record_access(self, page_id: int):
        page_id = _page_id(page_id)
        if page_id not in self._order:
            raise StorageError('BUFFER_POLICY_INVALID_STATE', 'LRU 访问了未登记页', page_id=page_id)
        self._order.move_to_end(page_id)

    def record_remove(self, page_id: int):
        page_id = _page_id(page_id)
        self._order.pop(page_id, None)

    def choose_victim(self, resident_page_ids):
        residents = [_page_id(v) for v in list(resident_page_ids)]
        if not residents or len(residents) != len(set(residents)):
            raise StorageError('BUFFER_POLICY_INVALID_STATE', '候选页集合无效')
        resident_set = set(residents)
        if not resident_set.issubset(set(self._order.keys())):
            raise StorageError('BUFFER_POLICY_INVALID_STATE', '候选页包含未在 LRU 中登记的页')
        for page_id in self._order:
            if page_id in resident_set:
                return page_id
        raise StorageError('BUFFER_POLICY_INVALID_STATE', '无法选择 LRU 淘汰页')


class FIFOPolicy(ReplacementPolicy):
    name = 'FIFO'

    def __init__(self):
        self._queue = deque()
        self._known = set()

    def record_insert(self, page_id: int):
        page_id = _page_id(page_id)
        if page_id in self._known:
            return
        self._known.add(page_id); self._queue.append(page_id)

    def record_access(self, page_id: int):
        page_id = _page_id(page_id)
        if page_id not in self._known:
            raise StorageError('BUFFER_POLICY_INVALID_STATE', 'FIFO 访问了未登记页', page_id=page_id)

    def record_remove(self, page_id: int):
        page_id = _page_id(page_id)
        if page_id not in self._known:
            return
        self._known.remove(page_id)
        self._queue = deque(v for v in self._queue if v != page_id)

    def choose_victim(self, resident_page_ids):
        residents = [_page_id(v) for v in list(resident_page_ids)]
        if not residents or len(residents) != len(set(residents)):
            raise StorageError('BUFFER_POLICY_INVALID_STATE', '候选页集合无效')
        resident_set = set(residents)
        if not resident_set.issubset(self._known):
            raise StorageError('BUFFER_POLICY_INVALID_STATE', '候选页包含未在 FIFO 中登记的页')
        for page_id in self._queue:
            if page_id in resident_set:
                return page_id
        raise StorageError('BUFFER_POLICY_INVALID_STATE', '无法选择 FIFO 淘汰页')


def make_policy(name: str) -> ReplacementPolicy:
    normalized = str(name).upper()
    if normalized == 'LRU':
        return LRUPolicy()
    if normalized == 'FIFO':
        return FIFOPolicy()
    raise StorageError('BUFFER_POLICY_INVALID', 'policy 只支持 LRU 或 FIFO')
