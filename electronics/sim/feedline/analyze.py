#!/usr/bin/env python3
"""Post-process gerber2ems results for the nRF9151 -> P822601 feedline.

Reads ems/results/Port_0_data.csv of each variant (port 0 = nRF9151 side, port 1 = matching-network side),
reports the line's S-parameters / impedance, and cascades the line with the antenna data
(Ant_P822601_S_parameters.s1p + the datasheet pi network, and the measured demo board EVB_...s1p)
to show the return loss the modem sees. Needs numpy + scikit-rf.
"""
import sys, os, glob, csv
import numpy as np
import skrf as rf

HERE = os.path.dirname(os.path.abspath(__file__))
SP = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'sparam')
VARIANTS = [v for v in ('asis', 'noufl', 'w135', 'cut', 'cut2', 'cutboth') if os.path.exists(os.path.join(HERE, v, 'ems', 'results', 'Port_0_data.csv'))]
BANDS = [('B28/B12 703-748', 703, 748), ('B5 824-849', 824, 849), ('B8 880-915', 880, 915), ('B3 1710-1785', 1710, 1785),
         ('B2 1850-1910', 1850, 1910), ('B1 1920-1980', 1920, 1980), ('B7 2500-2570', 2500, 2570), ('n78 3400-3600', 3400, 3600)]
db = lambda x: 20 * np.log10(np.abs(x))


def load_port0(variant):
    """Return (f_Hz, S11, S21, Zin) measured at port 0 with port 1 terminated in 50 ohm."""
    rows = list(csv.reader(open(os.path.join(HERE, variant, 'ems', 'results', 'Port_0_data.csv'))))
    hdr = rows[0]; data = np.array(rows[1:], dtype=float)
    col = {h.strip(): i for i, h in enumerate(hdr)}
    f = data[:, col['Frequency [MHz]']] * 1e6
    s11 = data[:, col['|S0-0| [-]']] * np.exp(1j * data[:, col['Arg(S0-0) [rad]']])
    s21 = data[:, col['|S1-0| [-]']] * np.exp(1j * data[:, col['Arg(S1-0) [rad]']])
    return f, s11, s21


def two_port_from_sym(f, s11, s21):
    """Build a reciprocal, symmetric-enough 2-port (S22 ~ S11) network for cascading."""
    s = np.zeros((len(f), 2, 2), complex)
    s[:, 0, 0] = s11; s[:, 1, 1] = s11; s[:, 0, 1] = s21; s[:, 1, 0] = s21
    return rf.Network(frequency=rf.Frequency.from_f(f, unit='Hz'), s=s, z0=50)


def pi_network(freq, Lp1=24e-9, Cs1=2.4e-12, Ls2=1.0e-9, Cp4=0.3e-12):
    """Datasheet p.12 pi network as a 2-port (port 1 = RF side, port 2 = antenna side)."""
    w = 2 * np.pi * freq.f
    def shunt(Y):
        A = np.ones_like(Y); return np.array([[A, np.zeros_like(Y)], [Y, A]]).transpose(2, 0, 1)
    def series(Z):
        A = np.ones_like(Z); return np.array([[A, Z], [np.zeros_like(Z), A]]).transpose(2, 0, 1)
    M = shunt(1j * w * Cp4) if Cp4 else np.tile(np.eye(2), (len(w), 1, 1))
    M = M @ series(1j * w * Ls2) @ series(1 / (1j * w * Cs1)) @ shunt(1 / (1j * w * Lp1))
    return rf.Network(frequency=freq, a=M, z0=50)


def worst_in_bands(f, gamma):
    return [db(gamma[(f >= lo * 1e6) & (f <= hi * 1e6)]).max() if np.any((f >= lo * 1e6) & (f <= hi * 1e6)) else np.nan for _, lo, hi in BANDS]


def main():
    ant = rf.Network(os.path.join(SP, 'Ant_P822601_S_parameters.s1p'))
    evb = rf.Network(os.path.join(SP, 'EVB_P822601-01_S_parameters.s1p'))
    print('bands:', ' '.join(f'{n.split()[0]:>8s}' for n, _, _ in BANDS))
    for variant in VARIANTS:
        f, s11, s21 = load_port0(variant)
        keep = (f >= 650e6) & (f <= 4.0e9)                      # antenna data covers 650 MHz .. 4 GHz
        f, s11, s21 = f[keep], s11[keep], s21[keep]
        line = two_port_from_sym(f, s11, s21)
        zin = 50 * (1 + s11) / (1 - s11)
        print(f'\n=== {variant} ===')
        for m in (700, 900, 1800, 2100, 2600, 3500, 3800):
            i = np.argmin(np.abs(f - m * 1e6))
            print(f'  {m:5d} MHz: |S11| {db(s11[i]):6.1f} dB  |S21| {db(s21[i]):6.2f} dB  Zin(50 ohm load) = {zin[i].real:5.1f}{zin[i].imag:+6.1f}j')
        # line + pi network + bare antenna
        fr = line.frequency
        a = ant.interpolate(fr); e = evb.interpolate(fr)
        pi = pi_network(fr)
        total_pi = line ** pi ** a       # modem -> line -> pi -> antenna
        total_evb = line ** e            # modem -> line -> (demo board incl. its matching)
        ref_pi = pi ** a                 # no feedline reference
        print('   line alone |S11| max :', ' '.join(f'{v:8.1f}' for v in worst_in_bands(f, s11)))
        print('  worst |S11| per band, dB:')
        print('   pi+antenna, no line :', ' '.join(f'{v:8.1f}' for v in worst_in_bands(fr.f, ref_pi.s[:, 0, 0])))
        print('   line+pi+antenna     :', ' '.join(f'{v:8.1f}' for v in worst_in_bands(fr.f, total_pi.s[:, 0, 0])))
        print('   demo board, no line :', ' '.join(f'{v:8.1f}' for v in worst_in_bands(fr.f, e.s[:, 0, 0])))
        print('   line+demo board     :', ' '.join(f'{v:8.1f}' for v in worst_in_bands(fr.f, total_evb.s[:, 0, 0])))
        print('   line |S21| worst, dB:', ' '.join(f'{v:8.2f}' for v in [db(s21[(f >= lo*1e6) & (f <= hi*1e6)]).min() for _, lo, hi in BANDS]))


if __name__ == '__main__':
    main()
