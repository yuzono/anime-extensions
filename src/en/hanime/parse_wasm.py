import struct

with open(r'C:\Users\Branden\IdeaProjects\anime-extensions\src\en\hanime\vendor.wasm', 'rb') as f:
    data = f.read()

def read_leb128(data, pos):
    result = 0
    shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if (byte & 0x80) == 0:
            break
        shift += 7
    return result, pos

def read_name(data, pos):
    length, pos = read_leb128(data, pos)
    name = data[pos:pos+length].decode('utf-8', errors='replace')
    return name, pos + length

def skip_leb128(data, pos):
    while data[pos] & 0x80:
        pos += 1
    pos += 1
    return pos

def skip_init_expr(data, pos):
    opcode = data[pos]
    pos += 1
    if opcode == 0x41:  # i32.const
        _, pos = read_leb128(data, pos)
    elif opcode == 0x42:  # i64.const
        while data[pos] & 0x80:
            pos += 1
        pos += 1
    elif opcode == 0x43:  # f32.const
        pos += 4
    elif opcode == 0x44:  # f64.const
        pos += 8
    elif opcode == 0x23:  # global.get
        _, pos = read_leb128(data, pos)
    elif opcode == 0x24:  # ref.null
        pos += 1
    pos += 1  # end opcode
    return pos

magic = data[0:4]
version = struct.unpack('<I', data[4:8])[0]
print(f"Magic: {magic}, Version: {version}")
pos = 8

SECTION_NAMES = {0:'Custom',1:'Type',2:'Import',3:'Function',4:'Table',5:'Memory',6:'Global',7:'Export',8:'Start',9:'Element',10:'Code',11:'Data',12:'DataCount'}

while pos < len(data):
    if pos >= len(data):
        break
    section_id = data[pos]
    pos += 1
    size, pos = read_leb128(data, pos)
    section_end = pos + size
    sname = SECTION_NAMES.get(section_id, f'Unknown({section_id})')
    
    if section_id == 2:  # Import
        print(f"\n========== IMPORT SECTION ==========")
        num_imports, pos = read_leb128(data, pos)
        for i in range(num_imports):
            module, pos = read_name(data, pos)
            field, pos = read_name(data, pos)
            kind = data[pos]
            pos += 1
            if kind == 0:  # Function
                type_idx, pos = read_leb128(data, pos)
                print(f"  func import[{i}]: {module}.{field} (type_idx={type_idx})")
            elif kind == 1:  # Table
                elem_type = data[pos]
                pos += 1
                flags, pos = read_leb128(data, pos)
                min_val, pos = read_leb128(data, pos)
                if flags & 1:
                    max_val, pos = read_leb128(data, pos)
                    print(f"  table import[{i}]: {module}.{field} (elem={elem_type}, min={min_val}, max={max_val})")
                else:
                    print(f"  table import[{i}]: {module}.{field} (elem={elem_type}, min={min_val})")
            elif kind == 2:  # Memory
                flags, pos = read_leb128(data, pos)
                min_val, pos = read_leb128(data, pos)
                print(f"  memory import[{i}]: {module}.{field} (min={min_val})")
            elif kind == 3:  # Global
                val_type = data[pos]
                pos += 1
                mutability = data[pos]
                pos += 1
                pos = skip_init_expr(data, pos)
                print(f"  global import[{i}]: {module}.{field} (type={val_type}, mut={mutability})")
    
    elif section_id == 7:  # Export
        print(f"\n========== EXPORT SECTION ==========")
        num_exports, pos = read_leb128(data, pos)
        for i in range(num_exports):
            name, pos = read_name(data, pos)
            kind = data[pos]
            pos += 1
            idx, pos = read_leb128(data, pos)
            kind_names = {0:'func',1:'table',2:'memory',3:'global'}
            print(f"  export[{i}]: {name} ({kind_names.get(kind, '?')}, idx={idx})")
    
    else:
        print(f"Section {section_id} ({sname}): size={size}")
    
    pos = section_end
